package com.easy.easyai.web.service

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class AsrTicketStoreTest {

    private val store = AsrTicketStore()

    private fun issue(userId: String = "alice", origin: String? = "https://app.example.com") =
        store.issue(
            userId = userId,
            appName = "easyai",
            owners = listOf(userId, "group-1"),
            origin = origin,
            configId = "row-1",
            modelId = "qwen-audio-3.1-asr-flash-streaming"
        )

    @Nested
    inner class Consumption {

        @Test
        fun `a ticket is bound to the caller and the round it was issued for`() {
            val ticket = assertNotNull(issue())
            assertEquals("alice", ticket.userId)
            assertEquals(listOf("alice", "group-1"), ticket.owners)
            assertEquals("https://app.example.com", ticket.origin)
            assertEquals("row-1", ticket.configId)
            // 256 bits, URL-safe: long enough that guessing is not a threat model.
            assertEquals(true, ticket.value.length >= 43)
        }

        @Test
        fun `a ticket can only be consumed once`() {
            val ticket = assertNotNull(issue())
            assertNotNull(store.consume(ticket.value))
            assertNull(store.consume(ticket.value))
        }

        @Test
        fun `a handed over ticket can only be claimed once`() {
            val ticket = assertNotNull(issue())
            val accepted = assertNotNull(store.consume(ticket.value))
            store.handOver(accepted)
            assertEquals("alice", assertNotNull(store.claim(ticket.value)).userId)
            // The socket handler runs once per upgrade; a second claim must not open a second round.
            assertNull(store.claim(ticket.value))
        }

        @Test
        fun `an unknown ticket consumes nothing`() {
            assertNull(store.consume("not-a-ticket"))
        }

        @Test
        fun `consuming one ticket leaves another intact`() {
            val first = assertNotNull(issue())
            val second = assertNotNull(issue())
            assertNotNull(store.consume(first.value))
            assertNotNull(store.consume(second.value))
        }
    }

    @Nested
    inner class Limits {

        @Test
        fun `a third pending ticket for one caller is refused`() {
            assertNotNull(issue())
            assertNotNull(issue())
            assertNull(issue(), "the cap is what keeps an abandoned tab from minting tickets forever")
        }

        @Test
        fun `consuming frees the caller's slot`() {
            val first = assertNotNull(issue())
            assertNotNull(issue())
            assertNull(issue())
            assertNotNull(store.consume(first.value))
            assertNotNull(issue())
        }

        @Test
        fun `another caller is not affected by the per-user cap`() {
            assertNotNull(issue("alice"))
            assertNotNull(issue("alice"))
            assertNotNull(issue("bob"))
        }
    }

    @Nested
    inner class Expiry {

        @Test
        fun `an expired ticket is neither consumable nor counted`() {
            val store = AsrTicketStore()
            val expired = assertNotNull(
                store.issue("alice", "easyai", listOf("alice"), null, "row-1", "model", ttlMillis = -1)
            )
            assertNull(store.consume(expired.value))
            assertEquals(0, store.pendingCount())
        }

        @Test
        fun `an expired ticket does not hold the caller's slot`() {
            val store = AsrTicketStore()
            assertNotNull(store.issue("alice", "easyai", listOf("alice"), null, "row-1", "model", ttlMillis = -1))
            assertNotNull(store.issue("alice", "easyai", listOf("alice"), null, "row-1", "model"))
            assertNotNull(store.issue("alice", "easyai", listOf("alice"), null, "row-1", "model"))
        }
    }
}
