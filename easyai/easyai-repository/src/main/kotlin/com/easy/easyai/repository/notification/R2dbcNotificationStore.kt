package com.easy.easyai.repository.notification

import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.case
import org.jetbrains.exposed.v1.core.count
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.intLiteral
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.less
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID

/**
 * R2DBC implementation of [NotificationStore].
 *
 * The badge predicates (`unread`, `actionable`) fold the deadline into the query instead of relying
 * on the stored status: a decision past `expiresAt` stops counting immediately even though its row
 * only flips to `EXPIRED` when someone actually reads it.
 */
class R2dbcNotificationStore(private val db: R2dbcDatabase) : NotificationStore {

    private val table = Tables.NotificationTable

    override suspend fun insertBatch(rows: List<Notification>): List<Notification> {
        if (rows.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val written = mutableListOf<Notification>()
        suspendTransaction(db) {
            // One lookup per source instead of one per row: a broadcast can address hundreds of
            // recipients, and the pre-check only ever asks about the keys in this batch.
            val existing = existingDedupeKeys(rows)
            val seen = mutableSetOf<String>()
            rows.forEach { row ->
                val generatedId = row.id.ifBlank { UUID.randomUUID().toString() }
                val record = row.copy(
                    id = generatedId,
                    // No event id means "this row is the whole event"; a fan-out passes one shared id.
                    eventId = row.eventId.ifBlank { generatedId },
                    createdAt = row.createdAt.takeIf { it > 0 } ?: now,
                )
                val rowDedupeKey = record.dedupeKey
                if (rowDedupeKey != null) {
                    val composite = dedupeComposite(record.source, record.userId, rowDedupeKey)
                    if (composite in existing || !seen.add(composite)) return@forEach
                }
                table.insert {
                    it[id] = record.id
                    it[eventId] = record.eventId
                    it[userId] = record.userId
                    it[notificationSource] = record.source
                    it[type] = record.type
                    it[direction] = record.direction.name
                    it[title] = record.title
                    it[body] = record.body
                    it[url] = record.url
                    it[scopeType] = record.scopeType
                    it[scopeId] = record.scopeId
                    it[scopeName] = record.scopeName
                    it[actorUserId] = record.actorUserId
                    it[actorName] = record.actorName
                    it[targetName] = record.targetName
                    it[actionStatus] = record.actionStatus.name
                    it[actionAt] = record.actionAt
                    it[actionBy] = record.actionBy
                    it[readAt] = record.readAt
                    it[createdAt] = record.createdAt
                    it[expiresAt] = record.expiresAt
                    it[dedupeKey] = record.dedupeKey
                    it[pendingKey] = pendingKeyOf(record)
                    it[payload] = record.payload
                }
                written += record
            }
        }
        return written
    }

    override suspend fun findByUser(
        userId: String,
        source: String,
        before: NotificationCursor?,
        limit: Int,
    ): List<Notification> = suspendTransaction(db) {
        val criterion = when (before) {
            null -> inboxOf(userId, source)
            else -> inboxOf(userId, source) and beyond(before)
        }
        table.selectAll()
            .where { criterion }
            .orderBy(table.createdAt to SortOrder.DESC, table.id to SortOrder.DESC)
            .limit(limit.coerceAtLeast(1))
            .toList()
            .map { it.toNotification() }
    }

    override suspend fun summary(userId: String, source: String, now: Long): NotificationSummary =
        suspendTransaction(db) {
            val inbox = inboxOf(userId, source)
            val unreadCondition = (table.direction inList INCOMING_OR_INFO) and table.readAt.isNull()
            val actionableCondition = pendingAction(now) and incoming()
            val outgoingCondition = pendingAction(now) and outgoing()
            // Union, not a sum: an unread request matches both halves and must still count once.
            val attentionCondition = (table.direction inList INCOMING_OR_INFO) and
                (table.readAt.isNull() or pendingAction(now))
            val unread = case().When(unreadCondition, intLiteral(1)).count()
            val actionable = case().When(actionableCondition, intLiteral(1)).count()
            val outgoing = case().When(outgoingCondition, intLiteral(1)).count()
            val attention = case().When(attentionCondition, intLiteral(1)).count()
            val row = table
                .select(unread, actionable, outgoing, attention)
                .where { inbox }
                .toList()
                .single()
            NotificationSummary(
                unread = row[unread].toInt(),
                actionable = row[actionable].toInt(),
                outgoing = row[outgoing].toInt(),
                attention = row[attention].toInt(),
            )
        }

    override suspend fun findById(id: String): Notification? = suspendTransaction(db) {
        table.selectAll()
            .where { table.id eq id }
            .toList()
            .firstOrNull()
            ?.toNotification()
    }

    override suspend fun findByEvent(eventId: String): List<Notification> = suspendTransaction(db) {
        table.selectAll()
            .where { table.eventId eq eventId }
            .orderBy(table.createdAt to SortOrder.DESC)
            .toList()
            .map { it.toNotification() }
    }

    override suspend fun findPendingAction(
        scopeType: String,
        scopeId: String,
        source: String,
        type: String,
    ): Notification? = suspendTransaction(db) {
        // The key is derived deterministically and carries the source; matching it uses the unique
        // index instead of scanning by scope columns, which have no index of their own.
        table.selectAll()
            .where { table.pendingKey eq pendingKeyOf(scopeType, scopeId, source, type) }
            .limit(1)
            .toList()
            .firstOrNull()
            ?.toNotification()
    }

    override suspend fun markRead(userId: String, id: String, readAt: Long): Int =
        suspendTransaction(db) {
            table.update({
                (table.id eq id) and (table.userId eq userId) and table.readAt.isNull()
            }) {
                it[table.readAt] = readAt
            }
        }

    override suspend fun markAllRead(userId: String, source: String, readAt: Long): Int =
        suspendTransaction(db) {
            // Outgoing copies are deliberately untouched: the initiator has nothing to read, and
            // clearing them would empty the "waiting for the other side" counter on one click.
            table.update({
                inboxOf(userId, source) and
                    (table.direction inList INCOMING_OR_INFO) and
                    table.readAt.isNull()
            }) {
                it[table.readAt] = readAt
            }
        }

    override suspend fun resolveEvent(
        eventId: String,
        status: NotificationActionStatus,
        byUserId: String,
        actionAt: Long,
    ): Int = suspendTransaction(db) {
        table.update({
            (table.eventId eq eventId) and (table.actionStatus eq NotificationActionStatus.PENDING.name)
        }) {
            it[actionStatus] = status.name
            // Qualified: the `actionAt` parameter shadows the column of the same name.
            it[table.actionAt] = actionAt
            it[actionBy] = byUserId
            // Releases the pending-action slot so the same object can be requested again.
            it[pendingKey] = null
        }
    }

    override suspend fun expireEvent(eventId: String, actionAt: Long): Int = suspendTransaction(db) {
        table.update({
            (table.eventId eq eventId) and (table.actionStatus eq NotificationActionStatus.PENDING.name)
        }) {
            it[actionStatus] = NotificationActionStatus.EXPIRED.name
            it[table.actionAt] = actionAt
            it[pendingKey] = null
        }
    }

    // ==================== predicates ====================

    private fun inboxOf(userId: String, source: String): Op<Boolean> =
        (table.userId eq userId) and (table.notificationSource eq source)

    private fun incoming(): Op<Boolean> = table.direction eq NotificationDirection.INCOMING.name

    private fun outgoing(): Op<Boolean> = table.direction eq NotificationDirection.OUTGOING.name

    /** A decision still open: pending and inside its deadline. */
    private fun pendingAction(now: Long): Op<Boolean> =
        (table.actionStatus eq NotificationActionStatus.PENDING.name) and
            (table.expiresAt.isNull() or (table.expiresAt greater now))

    /** Keyset predicate: everything strictly older than the last row of the previous page. */
    private fun beyond(cursor: NotificationCursor): Op<Boolean> =
        (table.createdAt less cursor.createdAt) or
            ((table.createdAt eq cursor.createdAt) and (table.id less cursor.id))

    // ==================== internals ====================

    /**
     * The `(user, source, dedupeKey)` triples of this batch that already have a row.
     *
     * Grouped by source because a batch normally comes from one publisher; each group costs a
     * single `IN` query, and the `user × key` cross product is at worst a superset of the rows the
     * batch actually asks about.
     */
    private suspend fun existingDedupeKeys(rows: List<Notification>): Set<String> {
        val existing = mutableSetOf<String>()
        rows.filter { it.dedupeKey != null }
            .groupBy { it.source }
            .forEach { (source, group) ->
                val users = group.map { it.userId }.distinct()
                val keys = group.map { it.dedupeKey }.filterNotNull().distinct()
                val rowsOfSource = table
                    .select(table.userId, table.dedupeKey)
                    .where {
                        (table.notificationSource eq source) and
                            (table.userId inList users) and
                            (table.dedupeKey inList keys)
                    }
                    .toList()
                rowsOfSource.forEach { row ->
                    val key = row[table.dedupeKey] ?: return@forEach
                    existing += dedupeComposite(source, row[table.userId], key)
                }
            }
        return existing
    }

    private fun dedupeComposite(source: String, userId: String, dedupeKey: String): String =
        "$source|$userId|$dedupeKey"

    private fun ResultRow.toNotification(): Notification = Notification(
        id = this[table.id],
        eventId = this[table.eventId],
        userId = this[table.userId],
        source = this[table.notificationSource],
        type = this[table.type],
        direction = directionOf(this[table.direction]),
        title = this[table.title],
        body = this[table.body],
        url = this[table.url],
        scopeType = this[table.scopeType],
        scopeId = this[table.scopeId],
        scopeName = this[table.scopeName],
        actorUserId = this[table.actorUserId],
        actorName = this[table.actorName],
        targetName = this[table.targetName],
        actionStatus = statusOf(this[table.actionStatus]),
        actionAt = this[table.actionAt],
        actionBy = this[table.actionBy],
        readAt = this[table.readAt],
        createdAt = this[table.createdAt],
        expiresAt = this[table.expiresAt],
        dedupeKey = this[table.dedupeKey],
        payload = this[table.payload],
    )

    /**
     * Unknown names fall back instead of throwing: a value written by a newer build of a host must
     * not make the whole inbox unreadable, and an unreadable inbox has no way to tell the user why.
     */
    private fun directionOf(name: String): NotificationDirection =
        NotificationDirection.entries.firstOrNull { it.name == name } ?: NotificationDirection.INFO

    private fun statusOf(name: String): NotificationActionStatus =
        NotificationActionStatus.entries.firstOrNull { it.name == name } ?: NotificationActionStatus.NONE

    private companion object {
        val INCOMING_OR_INFO = listOf(NotificationDirection.INCOMING.name, NotificationDirection.INFO.name)

        /**
         * The stored `scope|source|type` of a live incoming decision, or `null` for any other row.
         *
         * Only the incoming copy takes the slot: the initiator's own copy must not block a second
         * request, and an informational row is never a decision. Nulling this on the way out is what
         * makes the unique index reusable.
         */
        fun pendingKeyOf(record: Notification): String? {
            if (record.direction != NotificationDirection.INCOMING) return null
            if (record.actionStatus != NotificationActionStatus.PENDING) return null
            val scopeType = record.scopeType ?: return null
            val scopeId = record.scopeId ?: return null
            return pendingKeyOf(scopeType, scopeId, record.source, record.type)
        }

        /** The inverse every lookup uses; changing the format here changes writer and reader together. */
        fun pendingKeyOf(scopeType: String, scopeId: String, source: String, type: String): String =
            "$scopeType|$scopeId|$source|$type"
    }
}
