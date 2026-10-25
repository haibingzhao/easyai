package com.easy.easyai.repository.notification

import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the `notification` contract against a real H2 schema.
 *
 * The two invariants worth pinning hardest are the ones the shared UI rests on: a decision belongs
 * to **both** sides as separate rows that resolve together, and exactly one live request may exist
 * per object — the `pending_key` unique index standing in for the partial index H2 cannot express.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcNotificationStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        // MODE=MYSQL mirrors the production URLs, so the test runs the SQL the server runs.
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///notification_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) },
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store() = R2dbcNotificationStore(db)

    private fun uid(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun row(
        userId: String,
        direction: NotificationDirection = NotificationDirection.INFO,
        status: NotificationActionStatus = NotificationActionStatus.NONE,
        source: String = SOURCE,
        type: String = "test_type",
        scopeType: String? = null,
        scopeId: String? = null,
        eventId: String = "",
        dedupeKey: String? = null,
        expiresAt: Long? = null,
    ) = Notification(
        eventId = eventId,
        userId = userId,
        source = source,
        type = type,
        direction = direction,
        title = "标题",
        body = "正文",
        url = "/notifications",
        scopeType = scopeType,
        scopeId = scopeId,
        actionStatus = status,
        expiresAt = expiresAt,
        dedupeKey = dedupeKey,
    )

    @Nested
    inner class Publish {

        @Test
        fun `each recipient gets its own row under one event id`() = runTest {
            val s = store()
            val target = uid("target")
            val actor = uid("actor")
            val eventId = uid("event")
            val written = s.insertBatch(
                listOf(
                    row(target, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, eventId = eventId),
                    row(actor, NotificationDirection.OUTGOING, NotificationActionStatus.PENDING, eventId = eventId),
                ),
            )

            assertEquals(2, written.size)
            assertTrue(written.all { it.id.isNotBlank() && it.createdAt > 0 })
            assertEquals(listOf(eventId), written.map { it.eventId }.distinct())
            assertEquals(2, s.findByEvent(eventId).size)
            assertEquals(NotificationDirection.INCOMING, s.findByUser(target, SOURCE).single().direction)
            assertEquals(NotificationDirection.OUTGOING, s.findByUser(actor, SOURCE).single().direction)
        }

        @Test
        fun `a repeated dedupe key is skipped for the same recipient`() = runTest {
            val s = store()
            val user = uid("user")
            val other = uid("user")
            val key = uid("dedupe")
            assertEquals(1, s.insertBatch(listOf(row(user, dedupeKey = key))).size)
            assertEquals(0, s.insertBatch(listOf(row(user, dedupeKey = key))).size)

            // The key is per recipient, so a sibling broadcast of the same event still lands.
            assertEquals(1, s.insertBatch(listOf(row(other, dedupeKey = key))).size)
            assertEquals(1, s.findByUser(user, SOURCE).size)
            assertEquals(1, s.findByUser(other, SOURCE).size)
        }

        @Test
        fun `rows without a dedupe key never collide`() = runTest {
            val s = store()
            val user = uid("user")
            s.insertBatch(listOf(row(user), row(user)))

            assertEquals(2, s.findByUser(user, SOURCE).size)
        }

        @Test
        fun `two live requests for one object are refused, a resolved one is not`() = runTest {
            val s = store()
            val scope = uid("scope")
            val first = uid("user")
            val second = uid("user")
            s.insertBatch(
                listOf(row(first, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, scopeType = "family", scopeId = scope)),
            )

            val duplicate = runCatching {
                s.insertBatch(
                    listOf(row(second, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, scopeType = "family", scopeId = scope)),
                )
            }
            assertTrue(duplicate.isFailure, "a second live request must violate the pending-key index")
            assertEquals(1, s.findByUser(first, SOURCE).size)

            // Freeing the slot is what lets the same object be requested again later. The rejected
            // batch rolled back entirely, so this event still has exactly one recipient's copy.
            val event = s.findByUser(first, SOURCE).single().eventId
            assertEquals(1, s.resolveEvent(event, NotificationActionStatus.REJECTED, first, System.currentTimeMillis()))
            assertTrue(
                runCatching {
                    s.insertBatch(
                        listOf(row(second, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, scopeType = "family", scopeId = scope)),
                    )
                }.isSuccess,
            )
        }

        @Test
        fun `a duplicate dedupe key skips only its own recipient in the batch`() = runTest {
            val s = store()
            val returning = uid("user")
            val fresh = uid("user")
            val key = uid("dedupe")
            s.insertBatch(listOf(row(returning, dedupeKey = key)))

            val written = s.insertBatch(listOf(row(returning, dedupeKey = key), row(fresh, dedupeKey = key)))

            assertEquals(1, written.size)
            assertEquals(fresh, written.single().userId)
            assertEquals(1, s.findByUser(returning, SOURCE).size)
            assertEquals(1, s.findByUser(fresh, SOURCE).size)

            // The same key twice inside one batch is also a single row.
            val sameBatch = uid("user")
            assertEquals(1, s.insertBatch(listOf(row(sameBatch, dedupeKey = "same"), row(sameBatch, dedupeKey = "same"))).size)
        }

        @Test
        fun `an outgoing copy holds no pending slot`() = runTest {
            val s = store()
            val scope = uid("scope")
            val first = uid("user")
            val second = uid("user")
            s.insertBatch(
                listOf(row(first, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, scopeType = "family", scopeId = scope)),
            )

            // The initiator's own copy must not collide with the recipient's slot.
            s.insertBatch(
                listOf(row(second, NotificationDirection.OUTGOING, NotificationActionStatus.PENDING, scopeType = "family", scopeId = scope)),
            )
            assertEquals(1, s.findByUser(second, SOURCE).size)
        }
    }

    @Nested
    inner class Reads {

        @Test
        fun `the inbox is newest first and stops at the source boundary`() = runTest {
            val s = store()
            val user = uid("user")
            s.insertBatch(listOf(row(user, source = SOURCE), row(user, source = "other-product")))

            val rows = s.findByUser(user, SOURCE)
            assertEquals(1, rows.size)
            assertEquals(SOURCE, rows.single().source)
        }

        @Test
        fun `a cursor continues where the previous page ended`() = runTest {
            val s = store()
            val user = uid("user")
            val created = System.currentTimeMillis()
            s.insertBatch(
                (0 until 5).map { index -> row(user).copy(id = uid("id"), createdAt = created - index) },
            )

            val firstPage = s.findByUser(user, SOURCE, limit = 2)
            val secondPage = s.findByUser(
                user,
                SOURCE,
                before = NotificationCursor(firstPage.last().createdAt, firstPage.last().id),
                limit = 2,
            )
            val thirdPage = s.findByUser(
                user,
                SOURCE,
                before = NotificationCursor(secondPage.last().createdAt, secondPage.last().id),
            )

            assertEquals(listOf(created, created - 1), firstPage.map { it.createdAt })
            assertEquals(listOf(created - 2, created - 3), secondPage.map { it.createdAt })
            assertEquals(listOf(created - 4), thirdPage.map { it.createdAt })
            assertEquals(
                5,
                (firstPage + secondPage + thirdPage).map { it.id }.distinct().size,
                "pages must neither repeat a row nor drop one",
            )
        }

        @Test
        fun `lookups by id and event are absolute`() = runTest {
            val s = store()
            val user = uid("user")
            val eventId = uid("event")
            val written = s.insertBatch(listOf(row(user, eventId = eventId)))
            val id = written.single().id

            assertNotNull(s.findById(id))
            assertNull(s.findById(uid("missing")))
            assertEquals(id, s.findByEvent(eventId).single().id)
            assertEquals(0, s.findByEvent(uid("missing-event")).size)
        }

        @Test
        fun `an overdue request is still found until it is expired`() = runTest {
            val s = store()
            val user = uid("user")
            val scope = uid("scope")
            val live = s.insertBatch(
                listOf(
                    row(
                        user,
                        NotificationDirection.INCOMING,
                        NotificationActionStatus.PENDING,
                        type = "owner_transfer",
                        scopeType = "family",
                        scopeId = scope,
                    ),
                ),
            ).single()

            assertNotNull(s.findPendingAction("family", scope, SOURCE, "owner_transfer"))

            val pastScope = uid("scope")
            val past = s.insertBatch(
                listOf(
                    row(
                        uid("user"),
                        NotificationDirection.INCOMING,
                        NotificationActionStatus.PENDING,
                        type = "owner_transfer",
                        scopeType = "family",
                        scopeId = pastScope,
                        expiresAt = System.currentTimeMillis() - 1,
                    ),
                ),
            ).single()
            // The overdue row still occupies the unique pending slot, so it has to be visible:
            // that is what lets the caller expire it before raising a new request.
            assertNotNull(s.findPendingAction("family", pastScope, SOURCE, "owner_transfer"))

            assertEquals(1, s.expireEvent(past.eventId, System.currentTimeMillis()))
            assertNull(s.findPendingAction("family", pastScope, SOURCE, "owner_transfer"))

            s.resolveEvent(live.eventId, NotificationActionStatus.ACCEPTED, user, System.currentTimeMillis())
            assertNull(s.findPendingAction("family", scope, SOURCE, "owner_transfer"))
        }

        @Test
        fun `another product's pending request in the same scope does not block this one`() = runTest {
            val s = store()
            val scope = uid("scope")
            s.insertBatch(
                listOf(
                    row(
                        uid("user"),
                        NotificationDirection.INCOMING,
                        NotificationActionStatus.PENDING,
                        source = "other-product",
                        type = "owner_transfer",
                        scopeType = "family",
                        scopeId = scope,
                    ),
                ),
            )
            // A different source is a different pending key, so the same object can be requested.
            s.insertBatch(
                listOf(
                    row(
                        uid("user"),
                        NotificationDirection.INCOMING,
                        NotificationActionStatus.PENDING,
                        source = SOURCE,
                        type = "owner_transfer",
                        scopeType = "family",
                        scopeId = scope,
                    ),
                ),
            )

            assertEquals(SOURCE, s.findPendingAction("family", scope, SOURCE, "owner_transfer")?.source)
            assertEquals(
                "other-product",
                s.findPendingAction("family", scope, "other-product", "owner_transfer")?.source,
            )
        }
    }

    @Nested
    inner class Summary {

        @Test
        fun `unread, actionable and outgoing are counted apart`() = runTest {
            val s = store()
            val user = uid("user")
            val other = uid("user")
            val event = uid("event")
            s.insertBatch(
                listOf(
                    // Unread informational item.
                    row(user),
                    // Unread incoming decision.
                    row(user, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, eventId = event),
                    // Its initiator's copy: outgoing, and not unread from their own point of view.
                    row(other, NotificationDirection.OUTGOING, NotificationActionStatus.PENDING, eventId = event),
                ),
            )

            val mine = s.summary(user, SOURCE)
            assertEquals(2, mine.unread)
            assertEquals(1, mine.actionable)
            assertEquals(0, mine.outgoing)
            // The unread request is in both halves; attention counts it once.
            assertEquals(2, mine.attention)

            val theirs = s.summary(other, SOURCE)
            // An outgoing copy is never "unread": the initiator wrote it and has nothing to open.
            assertEquals(0, theirs.unread)
            assertEquals(0, theirs.actionable)
            assertEquals(1, theirs.outgoing)
            assertEquals(0, theirs.attention)
        }

        @Test
        fun `a past-deadline decision stops counting before its row is rewritten`() = runTest {
            val s = store()
            val user = uid("user")
            s.insertBatch(
                listOf(
                    row(
                        user,
                        NotificationDirection.INCOMING,
                        NotificationActionStatus.PENDING,
                        expiresAt = System.currentTimeMillis() - 1,
                    ),
                ),
            )

            assertEquals(0, s.summary(user, SOURCE).actionable)
            assertEquals(1, s.summary(user, SOURCE).unread)
            // Expired and never opened still asks for attention once, via the unread half.
            assertEquals(1, s.summary(user, SOURCE).attention)
        }

        @Test
        fun `reading clears unread but never a live decision`() = runTest {
            val s = store()
            val user = uid("user")
            val rows = s.insertBatch(
                listOf(
                    row(user),
                    row(user, NotificationDirection.INCOMING, NotificationActionStatus.PENDING),
                ),
            )
            assertEquals(2, s.summary(user, SOURCE).unread)
            assertEquals(2, s.summary(user, SOURCE).attention)

            assertEquals(1, s.markRead(user, rows.first().id, System.currentTimeMillis()))
            assertEquals(1, s.summary(user, SOURCE).unread)
            assertEquals(1, s.summary(user, SOURCE).actionable)
            assertEquals(1, s.summary(user, SOURCE).attention)

            // Read but still undecided: it leaves `unread`, stays in `attention`.
            assertEquals(1, s.markRead(user, rows[1].id, System.currentTimeMillis()))
            assertEquals(0, s.summary(user, SOURCE).unread)
            assertEquals(1, s.summary(user, SOURCE).actionable)
            assertEquals(1, s.summary(user, SOURCE).attention)

            assertEquals(0, s.markRead(user, rows.first().id, System.currentTimeMillis()))
        }

        @Test
        fun `mark-all-read leaves the initiator's copies alone`() = runTest {
            val s = store()
            val reader = uid("user")
            val initiator = uid("user")
            val event = uid("event")
            s.insertBatch(
                listOf(
                    row(reader),
                    row(reader, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, eventId = event),
                    row(initiator, NotificationDirection.OUTGOING, NotificationActionStatus.PENDING, eventId = event),
                ),
            )

            assertEquals(2, s.markAllRead(reader, SOURCE, System.currentTimeMillis()))
            assertEquals(0, s.summary(reader, SOURCE).unread)
            // Cleared unread does not clear the decision that is still waiting.
            assertEquals(1, s.summary(reader, SOURCE).attention)
            assertEquals(0, s.markAllRead(initiator, SOURCE, System.currentTimeMillis()))
            assertEquals(1, s.summary(initiator, SOURCE).outgoing)
            assertEquals(0, s.summary(initiator, SOURCE).attention)
        }
    }

    @Nested
    inner class Resolve {

        @Test
        fun `one decision settles every copy of the event`() = runTest {
            val s = store()
            val target = uid("target")
            val actor = uid("actor")
            val event = uid("event")
            s.insertBatch(
                listOf(
                    row(target, NotificationDirection.INCOMING, NotificationActionStatus.PENDING, eventId = event),
                    row(actor, NotificationDirection.OUTGOING, NotificationActionStatus.PENDING, eventId = event),
                ),
            )

            val now = System.currentTimeMillis()
            assertEquals(2, s.resolveEvent(event, NotificationActionStatus.ACCEPTED, target, now))
            assertEquals(0, s.resolveEvent(event, NotificationActionStatus.REJECTED, actor, now))

            s.findByEvent(event).forEach { copy ->
                assertEquals(NotificationActionStatus.ACCEPTED, copy.actionStatus)
                assertEquals(now, copy.actionAt)
                assertEquals(target, copy.actionBy)
            }
            assertEquals(0, s.summary(target, SOURCE).actionable)
            assertEquals(0, s.summary(actor, SOURCE).outgoing)
        }

        @Test
        fun `expiry is guarded so a concurrent answer wins`() = runTest {
            val s = store()
            val user = uid("user")
            val answered = s.insertBatch(
                listOf(row(user, NotificationDirection.INCOMING, NotificationActionStatus.PENDING)),
            ).single()
            s.resolveEvent(answered.eventId, NotificationActionStatus.ACCEPTED, user, System.currentTimeMillis())

            assertEquals(0, s.expireEvent(answered.eventId, System.currentTimeMillis()))
            assertEquals(NotificationActionStatus.ACCEPTED, s.findById(answered.id)?.actionStatus)

            val open = s.insertBatch(
                listOf(row(user, NotificationDirection.INCOMING, NotificationActionStatus.PENDING)),
            ).single()
            assertEquals(1, s.expireEvent(open.eventId, System.currentTimeMillis()))
            assertEquals(NotificationActionStatus.EXPIRED, s.findById(open.id)?.actionStatus)
            assertTrue(s.findByUser(user, SOURCE).none { it.id == open.id && it.actionStatus == NotificationActionStatus.PENDING })
        }
    }

    private companion object {
        const val SOURCE = "test-source"
    }
}
