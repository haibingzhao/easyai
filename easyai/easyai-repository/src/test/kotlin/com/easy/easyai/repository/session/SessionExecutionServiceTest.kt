package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.ChatSession
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [SessionExecutionService].
 * Uses MockK to verify DB interaction patterns and lifecycle guarantees.
 */
class SessionExecutionServiceTest {

    private lateinit var sessionStore: AsyncSessionStore
    private lateinit var service: SessionExecutionService

    @BeforeEach
    fun setup() {
        sessionStore = mockk(relaxed = true)
        // Service is created inside runTest with the test dispatcher injected
        // (see individual test methods)
    }

    private fun createService(scheduler: TestCoroutineScheduler): SessionExecutionService =
        SessionExecutionService(sessionStore, StandardTestDispatcher(scheduler))

    @Nested
    inner class `beginExecution` {

        @Test
        fun `marks streaming and resets endReason`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1")

            // Allow fire-and-forget coroutine to complete
            testScheduler.advanceUntilIdle()

            coVerify { sessionStore.updateStatus("s1", "streaming", "user1") }
            coVerify { sessionStore.saveEndReason("s1", "normal", "user1") }
        }

        @Test
        fun `resetEndReason=false skips saveEndReason`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1", resetEndReason = false)

            testScheduler.advanceUntilIdle()

            coVerify { sessionStore.updateStatus("s1", "streaming", "user1") }
            coVerify(exactly = 0) { sessionStore.saveEndReason(any(), any(), any()) }
        }

        @Test
        fun `registers in local registry`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1")

            assertTrue(service.isLocallyExecuting("s1"))
            assertEquals(1, service.getActiveSessionCount())
        }

        @Test
        fun `overwrite semantics - second begin replaces first`() = runTest {
            service = createService(testScheduler)
            val handle1 = service.beginExecution("s1", "user1")
            val handle2 = service.beginExecution("s1", "user1")

            assertTrue(service.isLocallyExecuting("s1"))
            assertEquals(1, service.getActiveSessionCount())
            // handle1 is no longer registered
            assertFalse(handle1 === handle2)
        }
    }

    @Nested
    inner class `endExecution` {

        @Test
        fun `saves endReason and marks active in order`() = runTest {
            service = createService(testScheduler)
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, "max_iterations")
            testScheduler.advanceUntilIdle()

            coVerify(ordering = io.mockk.Ordering.ORDERED) {
                sessionStore.saveEndReason("s1", "max_iterations", "user1")
                sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming")
            }
            assertFalse(service.isLocallyExecuting("s1"))
        }

        @Test
        fun `null endReason skips saveEndReason`() = runTest {
            service = createService(testScheduler)
            val handle = service.beginExecution("s1", "user1", resetEndReason = false)
            testScheduler.advanceUntilIdle()

            service.endExecution(handle, endReason = null)
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { sessionStore.saveEndReason(any(), any(), any()) }
            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
        }

        @Test
        fun `no-op when handle already removed by cancel`() = runTest {
            service = createService(testScheduler)
            val handle = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            // Cancel removes the handle first
            service.cancelExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            // Clear verification state
            io.mockk.clearMocks(sessionStore, answers = false)
            coEvery { sessionStore.updateStatus(any(), any(), any(), any()) } returns Unit
            coEvery { sessionStore.saveEndReason(any(), any(), any()) } returns Unit

            // endExecution should be no-op
            service.endExecution(handle, "normal")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { sessionStore.saveEndReason(any(), any(), any()) }
            coVerify(exactly = 0) { sessionStore.updateStatus(any(), any(), any(), any()) }
        }

        @Test
        fun `stale handle after re-register is no-op`() = runTest {
            service = createService(testScheduler)
            val handle1 = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            // Re-register (SSE reconnect)
            val handle2 = service.beginExecution("s1", "user1")
            testScheduler.advanceUntilIdle()

            io.mockk.clearMocks(sessionStore, answers = false)
            coEvery { sessionStore.updateStatus(any(), any(), any(), any()) } returns Unit
            coEvery { sessionStore.saveEndReason(any(), any(), any()) } returns Unit

            // Old handle's endExecution is no-op
            service.endExecution(handle1, "normal")
            testScheduler.advanceUntilIdle()

            coVerify(exactly = 0) { sessionStore.updateStatus(any(), any(), any(), any()) }

            // New handle's endExecution works normally
            service.endExecution(handle2, "normal")
            testScheduler.advanceUntilIdle()

            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
        }
    }

    @Nested
    inner class `cancelExecution` {

        @Test
        fun `aborts session and persists cancelled state`() = runTest {
            service = createService(testScheduler)
            val session = mockk<ChatSession>(relaxed = true)
            service.beginExecution("s1", "user1", session = session)
            testScheduler.advanceUntilIdle()

            service.cancelExecution("s1", "user1")

            verify { session.abort() }
            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
            coVerify { sessionStore.saveEndReason("s1", "cancelled", "user1") }
            assertFalse(service.isLocallyExecuting("s1"))
        }

        @Test
        fun `no session - still updates DB`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1", session = null)
            testScheduler.advanceUntilIdle()

            service.cancelExecution("s1", "user1")

            coVerify { sessionStore.updateStatus("s1", "active", "user1", expectedStatus = "streaming") }
            coVerify { sessionStore.saveEndReason("s1", "cancelled", "user1") }
        }

        @Test
        fun `unknown session - attempts DB update for cross-instance semantics`() = runTest {
            service = createService(testScheduler)
            service.cancelExecution("unknown", "user1")

            coVerify { sessionStore.updateStatus("unknown", "active", "user1", expectedStatus = "streaming") }
            coVerify { sessionStore.saveEndReason("unknown", "cancelled", "user1") }
        }
    }

    @Nested
    inner class `query methods` {

        @Test
        fun `isLocallyExecuting returns correct state`() = runTest {
            service = createService(testScheduler)
            assertFalse(service.isLocallyExecuting("s1"))

            val handle = service.beginExecution("s1", "user1")
            assertTrue(service.isLocallyExecuting("s1"))

            service.endExecution(handle)
            assertFalse(service.isLocallyExecuting("s1"))
        }

        @Test
        fun `getActiveSession returns session or null`() = runTest {
            service = createService(testScheduler)
            val session = mockk<ChatSession>(relaxed = true)
            service.beginExecution("s1", "user1", session = session)

            assertEquals(session, service.getActiveSession("s1"))
            assertNull(service.getActiveSession("unknown"))
        }

        @Test
        fun `getActiveSessionCount tracks registrations`() = runTest {
            service = createService(testScheduler)
            assertEquals(0, service.getActiveSessionCount())

            val h1 = service.beginExecution("s1", "user1")
            assertEquals(1, service.getActiveSessionCount())

            service.beginExecution("s2", "user2")
            assertEquals(2, service.getActiveSessionCount())

            service.endExecution(h1)
            assertEquals(1, service.getActiveSessionCount())
        }
    }

    @Nested
    inner class `destroy` {

        @Test
        fun `clears registry and resets DB status`() = runTest {
            service = createService(testScheduler)
            service.beginExecution("s1", "user1")
            service.beginExecution("s2", "user2")
            testScheduler.advanceUntilIdle()

            service.destroy()

            assertEquals(0, service.getActiveSessionCount())
            assertFalse(service.isLocallyExecuting("s1"))
            assertFalse(service.isLocallyExecuting("s2"))
        }
    }

    @Nested
    inner class `null sessionStore` {

        @Test
        fun `works without DB - registry only`() = runTest {
            val noDbService = SessionExecutionService(null, StandardTestDispatcher(testScheduler))

            val handle = noDbService.beginExecution("s1", "user1")
            assertTrue(noDbService.isLocallyExecuting("s1"))

            noDbService.endExecution(handle, "normal")
            assertFalse(noDbService.isLocallyExecuting("s1"))
        }
    }
}
