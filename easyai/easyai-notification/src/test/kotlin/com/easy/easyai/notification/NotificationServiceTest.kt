package com.easy.easyai.notification

import com.easy.easyai.push.PushMessage
import com.easy.easyai.push.PushNotifier
import com.easy.easyai.repository.notification.Notification
import com.easy.easyai.repository.notification.NotificationActionStatus
import com.easy.easyai.repository.notification.NotificationCursor
import com.easy.easyai.repository.notification.NotificationDirection
import com.easy.easyai.repository.notification.NotificationStore
import com.easy.easyai.repository.notification.NotificationSummary
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * The generic half of the notification lifecycle: who may answer, what the state machine allows,
 * and how one event becomes several recipient-shaped rows.
 *
 * The store is mocked, so these tests read the rules rather than SQL — the SQL side is pinned by
 * `R2dbcNotificationStoreTest`.
 */
class NotificationServiceTest {

    private val properties = NotificationProperties().apply { source = "home" }
    private val store = mockk<NotificationStore>(relaxed = true)
    private val push = mockk<PushNotifier>(relaxed = true)
    private val accepted = mutableListOf<Notification>()
    private val validated = mutableListOf<String>()

    private val handler = object : NotificationActionHandler {
        override val type = "owner_transfer"
        override suspend fun validate(notification: Notification, responderUserId: String): Any? {
            validated += responderUserId
            return "ctx"
        }

        override suspend fun onAccepted(notification: Notification, context: Any?) {
            accepted += notification
        }
    }

    private fun service(handlers: List<NotificationActionHandler> = listOf(handler), withPush: Boolean = true) =
        NotificationService(store, properties, if (withPush) push else null, handlers)

    private fun row(
        userId: String = "u1",
        direction: NotificationDirection = NotificationDirection.INCOMING,
        status: NotificationActionStatus = NotificationActionStatus.PENDING,
        eventId: String = "e1",
        type: String = "owner_transfer",
        readAt: Long? = null,
        expiresAt: Long? = null,
        createdAt: Long = 1_000L,
    ) = Notification(
        id = "$userId-$eventId",
        eventId = eventId,
        userId = userId,
        source = "home",
        type = type,
        direction = direction,
        title = "标题",
        body = "正文",
        url = "/notifications",
        scopeType = "family",
        scopeId = "f1",
        actionStatus = status,
        readAt = readAt,
        expiresAt = expiresAt,
        createdAt = createdAt,
    )

    private val event = NotificationEvent(
        type = "owner_transfer",
        title = "把「家」转让给你",
        body = "点击接受后你将成为户主",
        url = "/notifications",
        scopeType = "family",
        scopeId = "f1",
        scopeName = "我的家",
        actorUserId = "owner",
        actorName = "老王",
        targetName = "小李",
    )

    @Nested
    inner class Publish {

        @Test
        fun `a broadcast gives each account its own row and skips the actor`() = runBlocking<Unit> {
            val captured = slot<List<Notification>>()
            coEvery { store.insertBatch(capture(captured)) } answers { captured.captured }

            val written = service().notify(listOf("a", "b", " ", "a", "owner"), event)

            assertThat(written.map { it.userId }).containsExactly("a", "b")
            assertThat(captured.captured.map { it.direction }).containsExactly(
                NotificationDirection.INFO,
                NotificationDirection.INFO,
            )
            assertThat(captured.captured.map { it.source }).containsOnly("home")
            // An informational event is not a decision, so it must not hold the pending slot.
            assertThat(captured.captured.map { it.actionStatus }).containsOnly(NotificationActionStatus.NONE)
            assertThat(captured.captured.map { it.expiresAt }).containsOnly(null)
        }

        @Test
        fun `a broadcast to nobody writes nothing`() = runBlocking<Unit> {
            assertThat(service().notify(listOf("owner"), event)).isEmpty()

            coVerify(exactly = 0) { store.insertBatch(any()) }
        }

        @Test
        fun `a request copies the event to both sides under one event id`() = runBlocking<Unit> {
            coEvery { store.findPendingAction(any(), any(), any(), any()) } returns null
            val captured = slot<List<Notification>>()
            coEvery { store.insertBatch(capture(captured)) } answers { captured.captured }

            val written = service().request("target", event)

            assertThat(written.map { it.direction }).containsExactly(
                NotificationDirection.INCOMING,
                NotificationDirection.OUTGOING,
            )
            assertThat(written.map { it.actionStatus }).containsOnly(NotificationActionStatus.PENDING)
            // Both copies carry the same deadline and the same display snapshots.
            assertThat(written.map { it.scopeName }.distinct()).containsExactly("我的家")
            assertThat(written.map { it.targetName }.distinct()).containsExactly("小李")
        }

        @Test
        fun `a request to yourself does not create an outgoing copy`() = runBlocking<Unit> {
            coEvery { store.findPendingAction(any(), any(), any(), any()) } returns null
            val captured = slot<List<Notification>>()
            coEvery { store.insertBatch(capture(captured)) } answers { captured.captured }

            service().request("owner", event.copy(actorUserId = "owner"))

            assertThat(captured.captured.map { it.direction }).containsExactly(NotificationDirection.INCOMING)
        }

        @Test
        fun `a live request for the same object is refused before it is written`() = runBlocking<Unit> {
            coEvery { store.findPendingAction("family", "f1", "home", "owner_transfer") } returns row()

            assertThatThrownBy { runBlocking { service().request("target", event) } }
                .isInstanceOf(NotificationConflictException::class.java)
                .hasMessageContaining("已有待处理")

            coVerify(exactly = 0) { store.insertBatch(any()) }
        }

        @Test
        fun `an overdue request is expired before the new one is written`() = runBlocking<Unit> {
            val stale = row(eventId = "stale", expiresAt = System.currentTimeMillis() - 1)
            coEvery { store.findPendingAction("family", "f1", "home", "owner_transfer") } returns stale
            val captured = slot<List<Notification>>()
            coEvery { store.insertBatch(capture(captured)) } answers { captured.captured }

            val written = service().request("target", event)

            // The stale row still owns the unique pending slot; expiring the whole event frees it.
            coVerify { store.expireEvent("stale", any()) }
            assertThat(written.map { it.eventId }).doesNotContain("stale")
            assertThat(captured.captured.map { it.direction }).containsExactly(
                NotificationDirection.INCOMING,
                NotificationDirection.OUTGOING,
            )
        }

        @Test
        fun `a racing duplicate action surfaces the index violation as a conflict`() = runBlocking<Unit> {
            coEvery { store.findPendingAction(any(), any(), any(), any()) } returns null
            coEvery { store.insertBatch(any()) } throws
                IllegalStateException("Unique index or primary key violation: uk_notification_pending")

            assertThatThrownBy { runBlocking { service().request("target", event) } }
                .isInstanceOf(NotificationConflictException::class.java)
        }

        @Test
        fun `a racing duplicate broadcast is a no-op, not a failure`() = runBlocking<Unit> {
            coEvery { store.insertBatch(any()) } throws
                IllegalStateException("Unique index or primary key violation: uk_notification_dedupe")

            assertThat(service().notify(listOf("a"), event)).isEmpty()
        }

        @Test
        fun `structural problems are rejected`() = runBlocking<Unit> {
            coEvery { store.findPendingAction(any(), any(), any(), any()) } returns null

            assertThatThrownBy { runBlocking { service().notify(listOf("a"), event.copy(title = " ")) } }
                .isInstanceOf(NotificationValidationException::class.java)
            assertThatThrownBy { runBlocking { service().request("target", event.copy(type = " ")) } }
                .isInstanceOf(NotificationValidationException::class.java)
            assertThatThrownBy { runBlocking { service().request(" ", event) } }
                .isInstanceOf(NotificationValidationException::class.java)
            assertThatThrownBy { runBlocking { service().request("t", event.copy(scopeId = null)) } }
                .isInstanceOf(NotificationValidationException::class.java)
        }

        @Test
        fun `a deep link must stay on this site`() = runBlocking<Unit> {
            coEvery { store.findPendingAction(any(), any(), any(), any()) } returns null
            coEvery { store.insertBatch(any()) } answers { firstArg() }

            listOf("https://evil.example/x", "//evil.example/x", "/\\evil.example", "notifications").forEach { bad ->
                assertThatThrownBy { runBlocking { service().notify(listOf("a"), event.copy(url = bad)) } }
                    .describedAs("rejects %s", bad)
                    .isInstanceOf(NotificationValidationException::class.java)
            }
            // A link that only carries a query or a fragment is still same-site.
            assertThat(service().notify(listOf("a"), event.copy(url = "/mobile.html#/notifications")))
                .hasSize(1)
        }

        @Test
        fun `long text is truncated to the column width instead of failing the insert`() = runBlocking<Unit> {
            val captured = slot<List<Notification>>()
            coEvery { store.insertBatch(capture(captured)) } answers { captured.captured }

            service().notify(listOf("a"), event.copy(title = "字".repeat(300), body = "字".repeat(2000)))

            assertThat(captured.captured.single().title).hasSize(200)
            assertThat(captured.captured.single().body).hasSize(1000)
        }

        @Test
        fun `over-wide identifiers are rejected instead of reaching the database`() = runBlocking<Unit> {
            coEvery { store.findPendingAction(any(), any(), any(), any()) } returns null
            coEvery { store.insertBatch(any()) } answers { firstArg() }

            listOf(
                event.copy(type = "t".repeat(51)),
                event.copy(scopeType = "s".repeat(51)),
                event.copy(scopeId = "s".repeat(65)),
                event.copy(actorUserId = "u".repeat(65)),
            ).forEach { bad ->
                assertThatThrownBy { runBlocking { service().request("target", bad) } }
                    .describedAs("rejects an identifier wider than its column")
                    .isInstanceOf(NotificationValidationException::class.java)
            }
            assertThatThrownBy { runBlocking { service().notify(listOf("u".repeat(65)), event) } }
                .isInstanceOf(NotificationValidationException::class.java)
            assertThatThrownBy { runBlocking { service().request("u".repeat(65), event) } }
                .isInstanceOf(NotificationValidationException::class.java)
            coVerify(exactly = 0) { store.insertBatch(any()) }

            // Exactly at the column width is still valid.
            service().request(
                "target",
                event.copy(type = "t".repeat(50), scopeId = "s".repeat(64), actorUserId = "u".repeat(64)),
            )
        }
    }

    @Nested
    inner class HandOff {

        @Test
        fun `every stored copy except the initiator's is pushed once, tagged by event`() = runBlocking<Unit> {
            coEvery { store.findPendingAction(any(), any(), any(), any()) } returns null
            coEvery { store.insertBatch(any()) } answers { firstArg() }
            coEvery { push.isEnabled() } returns true

            val users = mutableListOf<String>()
            val messages = mutableListOf<PushMessage>()
            val written = service().request("target", event)

            assertThat(written.map { it.eventId }.distinct()).hasSize(1)
            coVerify(exactly = 1) { push.enqueue(capture(users), capture(messages)) }
            assertThat(users).containsExactly("target")
            // One tag for the whole event: a re-delivery replaces the banner instead of stacking it.
            assertThat(messages.single().tag).isEqualTo(written.first().eventId)
            assertThat(messages.single().url).isEqualTo("/notifications")
            assertThat(messages.single().icon).isEqualTo(properties.pushIcon)
        }

        @Test
        fun `a disabled channel or property never hands off`() = runBlocking<Unit> {
            coEvery { push.isEnabled() } returns false
            service().notify(listOf("a"), event)
            coVerify(exactly = 0) { push.enqueue(any(), any()) }

            coEvery { push.isEnabled() } returns true
            properties.pushEnabled = false
            service().notify(listOf("b"), event)
            coVerify(exactly = 0) { push.enqueue(any(), any()) }
            properties.pushEnabled = true
        }

        @Test
        fun `a service without the push module publishes anyway`() = runBlocking<Unit> {
            coEvery { store.insertBatch(any()) } answers { firstArg() }

            assertThat(service(withPush = false).notify(listOf("a"), event)).hasSize(1)
        }

        @Test
        fun `a throwing channel cannot fail a saved event`() = runBlocking<Unit> {
            coEvery { store.insertBatch(any()) } answers { firstArg() }
            coEvery { push.isEnabled() } returns true
            coEvery { push.enqueue(any(), any()) } throws IllegalStateException("transport gone")

            assertThat(service().notify(listOf("a"), event)).hasSize(1)
        }
    }

    @Nested
    inner class Inbox {

        @Test
        fun `the page is trimmed and only a full over-fetch hands back a cursor`() = runBlocking<Unit> {
            val rows = (1..3).map { row(userId = "a", eventId = "e$it", createdAt = 1_000L - it) }
            coEvery { store.findByUser("a", "home", null, 3) } returns rows
            coEvery { store.summary(any(), any(), any()) } returns NotificationSummary(1, 1, 0, 1)

            val page = service().inbox("a", limit = 2)

            assertThat(page.items).hasSize(2)
            assertThat(page.nextCursor).isEqualTo(NotificationCursor(rows[1].createdAt, rows[1].id).encode())
            assertThat(page.summary.actionable).isEqualTo(1)

            coEvery { store.findByUser("a", "home", null, 3) } returns rows.take(2)
            assertThat(service().inbox("a", limit = 2).nextCursor).isNull()
        }

        @Test
        fun `a malformed cursor restarts from the newest page`() = runBlocking<Unit> {
            coEvery { store.findByUser(any(), any(), any(), any()) } returns emptyList()

            service().inbox("a", cursor = "not-a-cursor")

            coVerify { store.findByUser("a", "home", null, 51) }
        }

        @Test
        fun `the configured list limit applies when the caller sends none`() = runBlocking<Unit> {
            properties.listLimit = 2
            coEvery { store.findByUser(any(), any(), any(), any()) } returns emptyList()

            service().inbox("a")

            coVerify { store.findByUser("a", "home", null, 3) }
        }

        @Test
        fun `flags are computed for the caller, not guessed by the client`() = runBlocking<Unit> {
            val now = System.currentTimeMillis()
            coEvery { store.findByUser(any(), any(), any(), any()) } returns listOf(
                row(userId = "a", direction = NotificationDirection.INCOMING, status = NotificationActionStatus.PENDING, expiresAt = now + 60_000),
                row(userId = "a", direction = NotificationDirection.OUTGOING, status = NotificationActionStatus.PENDING, eventId = "e2"),
                row(userId = "a", direction = NotificationDirection.INFO, status = NotificationActionStatus.NONE, eventId = "e3", readAt = now),
            )

            val items = service().inbox("a").items

            assertThat(items[0].canRespond).isTrue()
            assertThat(items[0].canWithdraw).isFalse()
            assertThat(items[0].unread).isTrue()
            // Waiting on someone else is not something the avatar should nag about.
            assertThat(items[1].canWithdraw).isTrue()
            assertThat(items[1].unread).isFalse()
            assertThat(items[2].canRespond).isFalse()
            assertThat(items[2].unread).isFalse()
        }

        @Test
        fun `a past-deadline decision expires on read before the client sees it`() = runBlocking<Unit> {
            val stale = row(userId = "a", expiresAt = System.currentTimeMillis() - 1)
            coEvery { store.findByUser(any(), any(), any(), any()) } returns listOf(stale)
            coEvery { store.expireEvent(stale.eventId, any()) } returns 1

            val item = service().inbox("a").items.single()

            assertThat(item.actionStatus).isEqualTo(NotificationActionStatus.EXPIRED)
            assertThat(item.canRespond).isFalse()
            coVerify { store.expireEvent(stale.eventId, any()) }
        }

        @Test
        fun `another account's row is not addressable`() = runBlocking<Unit> {
            coEvery { store.findById("x") } returns row(userId = "someone-else")
            coEvery { store.findById("y") } returns row(userId = "a").copy(source = "trading")
            coEvery { store.findById("missing") } returns null

            assertThatThrownBy { runBlocking { service().markRead("a", "x") } }
                .isInstanceOf(NotificationNotFoundException::class.java)
            assertThatThrownBy { runBlocking { service().markRead("a", "y") } }
                .isInstanceOf(NotificationNotFoundException::class.java)
            assertThatThrownBy { runBlocking { service().respond("a", "missing", NotificationDecision.ACCEPT) } }
                .isInstanceOf(NotificationNotFoundException::class.java)
        }

        @Test
        fun `reading clears unread and never settles the decision`() = runBlocking<Unit> {
            val open = row(userId = "a")
            coEvery { store.findById("a-e1") } returns open

            val view = service().markRead("a", "a-e1")

            assertThat(view.readAt).isNotNull()
            assertThat(view.actionStatus).isEqualTo(NotificationActionStatus.PENDING)
            assertThat(view.canRespond).isTrue()
            coVerify { store.markRead("a", "a-e1", any()) }
            coVerify(exactly = 0) { store.resolveEvent(any(), any(), any(), any()) }

            coEvery { store.findById("a-e1") } returns open.copy(readAt = 500L)
            service().markRead("a", "a-e1")
            coVerify(exactly = 1) { store.markRead(any(), any(), any()) }
        }

        @Test
        fun `mark-all-read is scoped to the caller's own source`() = runBlocking<Unit> {
            coEvery { store.markAllRead(any(), any(), any()) } returns 3

            assertThat(service().markAllRead("a")).isEqualTo(3)

            coVerify { store.markAllRead("a", "home", any()) }
        }
    }

    @Nested
    inner class Respond {

        @Test
        fun `each decision is only open on its own side of the event`() = runBlocking<Unit> {
            coEvery { store.findById(any()) } returns row(userId = "a", direction = NotificationDirection.INCOMING)

            assertThatThrownBy {
                runBlocking { service().respond("a", "a-e1", NotificationDecision.WITHDRAW) }
            }.isInstanceOf(NotificationForbiddenException::class.java)

            coEvery { store.findById(any()) } returns row(userId = "a", direction = NotificationDirection.OUTGOING)
            assertThatThrownBy {
                runBlocking { service().respond("a", "a-e1", NotificationDecision.ACCEPT) }
            }.isInstanceOf(NotificationForbiddenException::class.java)
        }

        @Test
        fun `a settled or expired event cannot be answered twice`() = runBlocking<Unit> {
            coEvery { store.findById(any()) } returns row(
                userId = "a",
                status = NotificationActionStatus.ACCEPTED,
            )
            assertThatThrownBy {
                runBlocking { service().respond("a", "a-e1", NotificationDecision.REJECT) }
            }.isInstanceOf(NotificationConflictException::class.java)

            coEvery { store.findById(any()) } returns row(
                userId = "a",
                status = NotificationActionStatus.EXPIRED,
            )
            assertThatThrownBy {
                runBlocking { service().respond("a", "a-e1", NotificationDecision.ACCEPT) }
            }.isInstanceOf(NotificationValidationException::class.java)
                .hasMessageContaining("已过期")
        }

        @Test
        fun `an unknown type is rejected before any state moves`() = runBlocking<Unit> {
            coEvery { store.findById(any()) } returns row(userId = "a", type = "unhandled_type")

            assertThatThrownBy {
                runBlocking { service().respond("a", "a-e1", NotificationDecision.ACCEPT) }
            }.isInstanceOf(NotificationValidationException::class.java)

            coVerify(exactly = 0) { store.resolveEvent(any(), any(), any(), any()) }
        }

        @Test
        fun `accepting validates, transitions once, then runs the side effect`() = runBlocking<Unit> {
            val open = row(userId = "a")
            coEvery { store.findById("a-e1") } returns open
            coEvery { store.resolveEvent(any(), any(), any(), any()) } returns 2

            val view = runBlocking { service().respond("a", "a-e1", NotificationDecision.ACCEPT) }

            assertThat(validated).containsExactly("a")
            assertThat(accepted.map { it.eventId }).containsExactly("e1")
            assertThat(view.actionStatus).isEqualTo(NotificationActionStatus.ACCEPTED)
            assertThat(view.canRespond).isFalse()
            assertThat(view.readAt).isNotNull()
            coVerify(exactly = 1) { store.resolveEvent("e1", NotificationActionStatus.ACCEPTED, "a", any()) }
        }

        @Test
        fun `a losing concurrent answer writes nothing and runs no side effect`() = runBlocking<Unit> {
            coEvery { store.findById("a-e1") } returns row(userId = "a")
            coEvery { store.resolveEvent(any(), any(), any(), any()) } returns 0

            assertThatThrownBy {
                runBlocking { service().respond("a", "a-e1", NotificationDecision.ACCEPT) }
            }.isInstanceOf(NotificationConflictException::class.java)

            assertThat(accepted).isEmpty()
        }

        @Test
        fun `withdrawing needs no handler validation but still calls the handler`() = runBlocking<Unit> {
            val own = row(userId = "owner", direction = NotificationDirection.OUTGOING)
            coEvery { store.findById(any()) } returns own
            coEvery { store.resolveEvent(any(), any(), any(), any()) } returns 2
            val withdrawn = mutableListOf<String>()
            val recording = object : NotificationActionHandler {
                override val type = "owner_transfer"
                override suspend fun onAccepted(notification: Notification, context: Any?) = Unit
                override suspend fun onWithdrawn(notification: Notification, context: Any?) {
                    withdrawn += notification.eventId
                }
            }

            runBlocking { service(listOf(recording)).respond("owner", "owner-e1", NotificationDecision.WITHDRAW) }

            assertThat(withdrawn).containsExactly("e1")
            assertThat(validated).isEmpty()
            coVerify(exactly = 1) {
                store.resolveEvent("e1", NotificationActionStatus.CANCELLED, "owner", any())
            }
        }

        @Test
        fun `withdrawing works after the type handler was removed`() = runBlocking<Unit> {
            coEvery { store.findById(any()) } returns row(userId = "owner", direction = NotificationDirection.OUTGOING)
            coEvery { store.resolveEvent(any(), any(), any(), any()) } returns 2

            val view = service(handlers = emptyList())
                .respond("owner", "owner-e1", NotificationDecision.WITHDRAW)

            assertThat(view.actionStatus).isEqualTo(NotificationActionStatus.CANCELLED)
            coVerify(exactly = 1) {
                store.resolveEvent("e1", NotificationActionStatus.CANCELLED, "owner", any())
            }
        }

        @Test
        fun `an expiry that loses the race reports the winning state`() = runBlocking<Unit> {
            val stale = row(userId = "a", expiresAt = System.currentTimeMillis() - 1)
            coEvery { store.findById("a-e1") } returnsMany listOf(
                stale,
                stale.copy(actionStatus = NotificationActionStatus.ACCEPTED, actionBy = "a"),
            )
            coEvery { store.expireEvent("e1", any()) } returns 0

            assertThatThrownBy {
                runBlocking { service().respond("a", "a-e1", NotificationDecision.ACCEPT) }
            }.isInstanceOf(NotificationConflictException::class.java)
                .hasMessageContaining("已处理")

            coVerify(exactly = 0) { store.resolveEvent(any(), any(), any(), any()) }
        }
    }
}
