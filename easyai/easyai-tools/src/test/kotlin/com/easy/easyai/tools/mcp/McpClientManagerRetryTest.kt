package com.easy.easyai.tools.mcp

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Covers the connect-round bookkeeping behind [McpClientManager.ensureUserConnected]: a round that
 * leaves a server failed must stay retryable (otherwise its tools are missing until a restart), the
 * retry must not run on the caller's path (the caller is a chat request resolving its tools, and a
 * handshake costs up to the server's timeout), and a deliberately disconnected server must not be
 * resurrected by a retry round.
 *
 * The server under test is a `local` one whose command does not exist, so its handshake fails fast
 * and leaves no process behind.
 */
class McpClientManagerRetryTest {

    private val broken = McpServerConfig(
        id = "srv-1",
        name = "broken",
        type = "local",
        command = listOf("/nonexistent/easyai-mcp-test-server"),
        timeoutSeconds = 1L,
        userId = "alice",
    )

    /** Store whose every round is counted; rounds after the first park on [gate] when one is given. */
    private class FakeStore(
        private val configs: List<McpServerConfig>,
        private val gate: CompletableDeferred<Unit>? = null,
    ) {
        val rounds = AtomicInteger()
        val mock: AsyncMcpServerStore = mockk(relaxed = true)

        init {
            coEvery { mock.findAllEnabled(any<String>()) } coAnswers {
                if (rounds.incrementAndGet() > 1) gate?.await()
                configs
            }
        }
    }

    private suspend fun awaitRounds(store: FakeStore, expected: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (roundsOf(store) < expected && System.currentTimeMillis() < deadline) {
            delay(20)
        }
        assertEquals(expected, roundsOf(store), "expected $expected connect round(s)")
    }

    private fun roundsOf(store: FakeStore) = store.rounds.get()

    /** Fails instead of hanging if a round that should be backgrounded ever runs on the caller. */
    private suspend fun ensureConnectedOffTheCallerPath(manager: McpClientManager, owner: String) {
        assertNotNull(
            withTimeoutOrNull(5_000) { manager.ensureUserConnected(owner) },
            "ensureUserConnected waited for the round instead of leaving it in background"
        )
    }

    @Nested
    inner class `A round that left a server failed` {

        @Test
        fun `does not close the owner, so a later call re-attempts it`() = runBlocking {
            val store = FakeStore(listOf(broken))
            val manager = McpClientManager(store.mock, failedRetryCooldownMs = 0L)
            try {
                manager.ensureUserConnected("alice")
                assertTrue(
                    manager.getStatus("broken", "alice") is McpServerStatus.Failed,
                    "expected the broken server to be reported failed"
                )
                assertEquals(1, roundsOf(store))

                manager.ensureUserConnected("alice")
                awaitRounds(store, 2)
            } finally {
                manager.destroy()
            }
        }

        @Test
        fun `is not re-attempted while the cooldown is armed`() = runBlocking {
            val store = FakeStore(listOf(broken))
            val manager = McpClientManager(store.mock)
            try {
                manager.ensureUserConnected("alice")
                assertEquals(1, roundsOf(store))

                manager.ensureUserConnected("alice")
                // Ample time for a round that should not happen: the default cooldown is a minute.
                delay(300)
                assertEquals(1, roundsOf(store), "cooldown must suppress the immediate retry")
            } finally {
                manager.destroy()
            }
        }

        @Test
        fun `runs off the caller's path`() = runBlocking {
            val gate = CompletableDeferred<Unit>()
            val store = FakeStore(listOf(broken), gate)
            val manager = McpClientManager(store.mock, failedRetryCooldownMs = 0L)
            try {
                manager.ensureUserConnected("alice")
                assertEquals(1, roundsOf(store))

                // The retry round parks on the gate, so an inline attempt could not return at all.
                ensureConnectedOffTheCallerPath(manager, "alice")
                assertTrue(gate.isActive, "expected the background round to be parked on the gate")

                gate.complete(Unit)
                awaitRounds(store, 2)
            } finally {
                manager.destroy()
            }
        }
    }

    @Nested
    inner class `A round with nothing failed` {

        @Test
        fun `closes the owner, so later calls are no-ops`() = runBlocking {
            val store = FakeStore(emptyList())
            val manager = McpClientManager(store.mock, failedRetryCooldownMs = 0L)
            try {
                manager.ensureUserConnected("bob")
                manager.ensureUserConnected("bob")
                delay(200)
                assertEquals(1, roundsOf(store))
            } finally {
                manager.destroy()
            }
        }

        @Test
        fun `does not resurrect a server the user disconnected`() = runBlocking {
            val gate = CompletableDeferred<Unit>()
            val store = FakeStore(listOf(broken), gate)
            val manager = McpClientManager(store.mock, failedRetryCooldownMs = 0L)
            try {
                manager.ensureUserConnected("alice")
                manager.disconnect("broken", "alice")
                assertEquals(McpServerStatus.Disabled, manager.getStatus("broken", "alice"))

                ensureConnectedOffTheCallerPath(manager, "alice")
                awaitRounds(store, 2)
                gate.complete(Unit)

                // A reconnect attempt would flip the row to Connecting and then Failed; skipping it
                // leaves the round with nothing failed, which closes the owner's initialization.
                delay(300)
                assertEquals(McpServerStatus.Disabled, manager.getStatus("broken", "alice"))
                ensureConnectedOffTheCallerPath(manager, "alice")
                delay(300)
                assertEquals(2, roundsOf(store), "the owner should be initialized, not retried again")
            } finally {
                manager.destroy()
            }
        }
    }
}
