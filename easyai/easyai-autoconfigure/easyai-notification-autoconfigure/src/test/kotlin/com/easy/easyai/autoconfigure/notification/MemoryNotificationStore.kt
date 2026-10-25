package com.easy.easyai.autoconfigure.notification

import com.easy.easyai.repository.notification.Notification
import com.easy.easyai.repository.notification.NotificationActionStatus
import com.easy.easyai.repository.notification.NotificationCursor
import com.easy.easyai.repository.notification.NotificationDirection
import com.easy.easyai.repository.notification.NotificationStore
import com.easy.easyai.repository.notification.NotificationSummary
import java.util.UUID

/**
 * In-memory stand-in for the R2DBC store, so an HTTP test exercises routing, ownership and error
 * mapping without a database. The row rules it mirrors are the ones the service relies on: a
 * decision lives in two copies sharing one event id, and only `PENDING` copies can be settled.
 */
internal class MemoryNotificationStore : NotificationStore {

    private val byId = LinkedHashMap<String, Notification>()

    override suspend fun insertBatch(rows: List<Notification>): List<Notification> {
        val now = System.currentTimeMillis()
        val written = rows.map { row ->
            val id = row.id.ifBlank { UUID.randomUUID().toString() }
            row.copy(
                id = id,
                eventId = row.eventId.ifBlank { id },
                createdAt = row.createdAt.takeIf { it > 0 } ?: now,
            )
        }
        written.forEach { byId[it.id] = it }
        return written
    }

    override suspend fun findByUser(
        userId: String,
        source: String,
        before: NotificationCursor?,
        limit: Int,
    ): List<Notification> = rowsOf(userId, source)
        .filter { row -> before == null || row.createdAt < before.createdAt ||
            (row.createdAt == before.createdAt && row.id < before.id) }
        .take(limit.coerceAtLeast(1))

    private fun rowsOf(userId: String, source: String): List<Notification> = byId.values
        .filter { it.userId == userId && it.source == source }
        .sortedWith(compareByDescending<Notification> { it.createdAt }.thenByDescending { it.id })

    override suspend fun summary(userId: String, source: String, now: Long): NotificationSummary {
        val mine = byId.values.filter { it.userId == userId && it.source == source }
        val visible = mine.filter { it.direction != NotificationDirection.OUTGOING }
        return NotificationSummary(
            unread = visible.count { it.readAt == null },
            actionable = mine.count { it.isLive(now) && it.direction == NotificationDirection.INCOMING },
            outgoing = mine.count { it.isLive(now) && it.direction == NotificationDirection.OUTGOING },
            attention = visible.count { it.readAt == null || it.isLive(now) },
        )
    }

    override suspend fun findById(id: String): Notification? = byId[id]

    override suspend fun findByEvent(eventId: String): List<Notification> =
        byId.values.filter { it.eventId == eventId }

    override suspend fun findPendingAction(scopeType: String, scopeId: String, source: String, type: String): Notification? =
        byId.values.firstOrNull {
            it.scopeType == scopeType && it.scopeId == scopeId && it.source == source && it.type == type &&
                it.direction == NotificationDirection.INCOMING &&
                it.actionStatus == NotificationActionStatus.PENDING
        }

    override suspend fun markRead(userId: String, id: String, readAt: Long): Int {
        val row = byId[id]?.takeIf { it.userId == userId && it.readAt == null } ?: return 0
        byId[id] = row.copy(readAt = readAt)
        return 1
    }

    override suspend fun markAllRead(userId: String, source: String, readAt: Long): Int {
        val matched = byId.values.filter {
            it.userId == userId && it.source == source && it.readAt == null &&
                it.direction != NotificationDirection.OUTGOING
        }
        matched.forEach { byId[it.id] = it.copy(readAt = readAt) }
        return matched.size
    }

    override suspend fun resolveEvent(
        eventId: String,
        status: NotificationActionStatus,
        byUserId: String,
        actionAt: Long,
    ): Int = settle(eventId, status) { it.copy(actionAt = actionAt, actionBy = byUserId) }

    override suspend fun expireEvent(eventId: String, actionAt: Long): Int =
        settle(eventId, NotificationActionStatus.EXPIRED) { it.copy(actionAt = actionAt) }

    private inline fun settle(
        eventId: String,
        status: NotificationActionStatus,
        andAlso: (Notification) -> Notification,
    ): Int {
        val pending = byId.values.filter {
            it.eventId == eventId && it.actionStatus == NotificationActionStatus.PENDING
        }
        pending.forEach { byId[it.id] = andAlso(it.copy(actionStatus = status)) }
        return pending.size
    }

    private fun Notification.isLive(now: Long): Boolean {
        val deadline = expiresAt
        return actionStatus == NotificationActionStatus.PENDING && (deadline == null || deadline > now)
    }

    /** Test helper: the ids a client would have received. */
    fun ids(): List<String> = byId.values.map { it.id }
}
