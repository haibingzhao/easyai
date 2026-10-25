package com.easy.easyai.autoconfigure.notification

import com.easy.easyai.notification.NotificationActionHandler
import com.easy.easyai.notification.NotificationConflictException
import com.easy.easyai.notification.NotificationEvent
import com.easy.easyai.notification.NotificationProperties
import com.easy.easyai.notification.NotificationService
import com.easy.easyai.repository.notification.Notification
import com.easy.easyai.repository.notification.NotificationActionStatus
import com.easy.easyai.repository.notification.NotificationCursor
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.test.web.reactive.server.WebTestClient

/**
 * The HTTP surface of the shared inbox, exercised without security.
 *
 * [com.easy.easyai.web.security.getCurrentUserId] falls back to `system` when no principal is
 * present, which is enough to prove the two things the API is responsible for: every route reaches
 * only the caller's **own** copy of an event, and each domain error lands on its HTTP status.
 */
class NotificationControllerTest {

    private val store = MemoryNotificationStore()
    private val properties = NotificationProperties().apply { source = "home" }
    private val accepted = mutableListOf<Notification>()
    private val service = NotificationService(
        store = store,
        properties = properties,
        push = null,
        handlers = listOf(TransferHandler(accepted)),
    )
    private val client = WebTestClient.bindToController(NotificationController(service)).build()

    private val event = NotificationEvent(
        type = "owner_transfer",
        title = "把「家」转让给你",
        body = "接受后你将成为户主",
        url = "/notifications",
        scopeType = "family",
        scopeId = "f1",
        scopeName = "我的家",
        actorUserId = OTHER,
        actorName = "老王",
        targetName = "小李",
    )

    /** A request addressed **to** the caller, who may answer it. The incoming copy comes first. */
    private fun raiseIncoming(scopeId: String = "f1"): List<Notification> =
        runBlocking { service.request(CALLER, event.copy(scopeId = scopeId)) }

    /** A request the caller **sent** to somebody else, which the caller may withdraw. */
    private fun raiseOutgoing(scopeId: String = "f1"): List<Notification> =
        runBlocking { service.request(OTHER, event.copy(scopeId = scopeId, actorUserId = CALLER)) }

    private fun eventCopies(eventId: String): List<Notification> = runBlocking { store.findByEvent(eventId) }

    // ==================== reads ====================

    @Test
    fun `the inbox carries the server-computed flags and badges`() {
        val copies = raiseIncoming()

        client.get().uri("/api/notifications").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.items[0].id").isEqualTo(copies.first().id)
            .jsonPath("$.items[0].direction").isEqualTo("INCOMING")
            .jsonPath("$.items[0].scopeName").isEqualTo("我的家")
            .jsonPath("$.items[0].actorName").isEqualTo("老王")
            .jsonPath("$.items[0].canRespond").isEqualTo(true)
            .jsonPath("$.items[0].canWithdraw").isEqualTo(false)
            .jsonPath("$.items[0].unread").isEqualTo(true)
            .jsonPath("$.summary.unread").isEqualTo(1)
            .jsonPath("$.summary.actionable").isEqualTo(1)
            .jsonPath("$.summary.outgoing").isEqualTo(0)
            .jsonPath("$.summary.attention").isEqualTo(1)
            .jsonPath("$.nextCursor").doesNotExist()
    }

    @Test
    fun `the summary alone answers the badge question`() {
        raiseIncoming()

        client.get().uri("/api/notifications/summary").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.unread").isEqualTo(1)
            .jsonPath("$.actionable").isEqualTo(1)
            .jsonPath("$.outgoing").isEqualTo(0)
            .jsonPath("$.attention").isEqualTo(1)
    }

    @Test
    fun `an unread page hands back a cursor that continues it`() {
        runBlocking {
            repeat(3) { index ->
                service.notify(
                    listOf(CALLER),
                    event.copy(type = "member_joined", title = "第 $index 条", scopeId = null, scopeType = null),
                )
            }
        }

        client.get().uri("/api/notifications?limit=2").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(2)
            .jsonPath("$.nextCursor").isNotEmpty

        // All three land in the same millisecond, so the cursor is the (createdAt, id) pair of the
        // last row of page one — the tie-break is what makes the second page non-empty.
        val oldest = runBlocking { store.findByUser(CALLER, "home", null, 2) }.last()
        val cursor = NotificationCursor(oldest.createdAt, oldest.id).encode()

        client.get().uri("/api/notifications?limit=2&cursor=$cursor").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.items.length()").isEqualTo(1)
            .jsonPath("$.nextCursor").doesNotExist()
    }

    // ==================== read state ====================

    @Test
    fun `reading an item clears unread but leaves the decision open`() {
        val targetId = raiseIncoming().first().id

        client.post().uri("/api/notifications/$targetId/read").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.readAt").isNotEmpty
            .jsonPath("$.unread").isEqualTo(false)
            .jsonPath("$.canRespond").isEqualTo(true)

        client.get().uri("/api/notifications/summary").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.unread").isEqualTo(0)
            .jsonPath("$.actionable").isEqualTo(1)
            // Read but undecided: the badge still has to say something is waiting.
            .jsonPath("$.attention").isEqualTo(1)
    }

    @Test
    fun `read-all counts only the caller's own unread copies`() {
        raiseIncoming()
        runBlocking {
            service.notify(listOf(OTHER), event.copy(type = "member_joined", scopeId = null, scopeType = null))
        }

        client.post().uri("/api/notifications/read-all").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.read").isEqualTo(1)
    }

    @Test
    fun `another account's copy is not reachable through the API`() {
        val initiatorId = raiseIncoming().last().id

        // That row exists and belongs to a real event — it simply is not the caller's.
        client.post().uri("/api/notifications/$initiatorId/read").exchange()
            .expectStatus().isNotFound
        client.post().uri("/api/notifications/no-such-row/read").exchange()
            .expectStatus().isNotFound
    }

    // ==================== decisions ====================

    @Test
    fun `the target accepting settles both copies and runs the handler once`() {
        val copies = raiseIncoming()

        client.post().uri("/api/notifications/${copies.first().id}/accept").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.actionStatus").isEqualTo("ACCEPTED")
            .jsonPath("$.canRespond").isEqualTo(false)

        assertThat(accepted).hasSize(1)
        // The initiator's copy settled with it, so there is nothing left for them to withdraw.
        assertThat(eventCopies(copies.first().eventId).map { it.actionStatus })
            .containsOnly(NotificationActionStatus.ACCEPTED)
    }

    @Test
    fun `each side may only act on its own side of the event`() {
        val targetId = raiseIncoming(scopeId = "f2").first().id
        val initiatorId = raiseOutgoing(scopeId = "f3").last().id

        client.post().uri("/api/notifications/$targetId/withdraw").exchange()
            .expectStatus().isForbidden
        client.post().uri("/api/notifications/$initiatorId/accept").exchange()
            .expectStatus().isForbidden
    }

    @Test
    fun `the initiator withdrawing cancels the event`() {
        val copies = raiseOutgoing(scopeId = "f4")

        client.post().uri("/api/notifications/${copies.last().id}/withdraw").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.actionStatus").isEqualTo("CANCELLED")

        assertThat(eventCopies(copies.first().eventId).map { it.actionStatus })
            .containsOnly(NotificationActionStatus.CANCELLED)
        client.get().uri("/api/notifications/summary").exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.outgoing").isEqualTo(0)
    }

    @Test
    fun `a second decision is a conflict, not a second side effect`() {
        val targetId = raiseIncoming(scopeId = "f5").first().id

        client.post().uri("/api/notifications/$targetId/accept").exchange().expectStatus().isOk
        client.post().uri("/api/notifications/$targetId/reject").exchange()
            .expectStatus().isEqualTo(409)

        assertThat(accepted).hasSize(1)
    }

    @Test
    fun `a second live request for the same object is refused`() {
        raiseIncoming(scopeId = "f6")

        val failure = runCatching { runBlocking { service.request(OTHER, event.copy(scopeId = "f6")) } }

        assertThat(failure.exceptionOrNull()).isInstanceOf(NotificationConflictException::class.java)
    }

    @Test
    fun `a type with no registered handler is rejected`() {
        val targetId = runBlocking {
            service.request(CALLER, event.copy(type = "unhandled", scopeId = "f8"))
        }.first().id

        client.post().uri("/api/notifications/$targetId/accept").exchange()
            .expectStatus().isBadRequest

        assertThat(accepted).isEmpty()
    }

    @Test
    fun `an expired request does not block the next one for the same object`() {
        val stale = runBlocking {
            service.request(CALLER, event.copy(scopeId = "f9", expiresAt = System.currentTimeMillis() - 1))
        }

        val fresh = runBlocking {
            service.request(OTHER, event.copy(scopeId = "f9", actorUserId = CALLER))
        }

        assertThat(fresh.map { it.actionStatus }).containsOnly(NotificationActionStatus.PENDING)
        assertThat(eventCopies(stale.first().eventId).map { it.actionStatus })
            .containsOnly(NotificationActionStatus.EXPIRED)
    }

    @Test
    fun `an expired request cannot be accepted`() {
        val targetId = runBlocking {
            service.request(CALLER, event.copy(scopeId = "f7", expiresAt = System.currentTimeMillis() - 1))
        }.first().id

        client.post().uri("/api/notifications/$targetId/accept").exchange()
            .expectStatus().isBadRequest

        assertThat(accepted).isEmpty()
    }

    private class TransferHandler(
        private val accepted: MutableList<Notification>,
    ) : NotificationActionHandler {
        override val type = "owner_transfer"
        override suspend fun validate(notification: Notification, responderUserId: String): Any = notification.scopeId!!
        override suspend fun onAccepted(notification: Notification, context: Any?) {
            accepted += notification
        }
    }

    private companion object {
        /** What [com.easy.easyai.web.security.getCurrentUserId] falls back to without authentication. */
        const val CALLER = "system"
        const val OTHER = "someone-else"
    }
}
