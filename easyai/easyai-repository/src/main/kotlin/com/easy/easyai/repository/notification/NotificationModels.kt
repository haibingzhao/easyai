package com.easy.easyai.repository.notification

/** Which side of the event the reading account is on. Computed once, by the publisher. */
enum class NotificationDirection {
    /** Someone is asking this account to decide. */
    INCOMING,

    /** This account asked someone else and is waiting. */
    OUTGOING,

    /** Information only — nobody has to act. */
    INFO,
}

/** Lifecycle of the decision an event carries. `NONE` means the notification asks for nothing. */
enum class NotificationActionStatus {
    NONE,
    PENDING,
    ACCEPTED,
    REJECTED,

    /** Withdrawn by the initiator. */
    CANCELLED,

    /** Deadline elapsed without a decision; written lazily on read, never by a scheduler. */
    EXPIRED,
}

/**
 * One notification as one account sees it — the row of [NotificationStore] minus the storage-only
 * columns (`pendingKey` is derived from [direction] / [actionStatus] / scope inside the store).
 *
 * [title], [body], [scopeName], [actorName] and [targetName] are **text snapshots taken at publish
 * time**: the inbox renders without touching any business table, which is what lets one inbox mix a
 * family invite and a trade order. Renaming a person or a family does not rewrite history.
 */
data class Notification(
    val id: String = "",
    /** Copies of the same business event share this key; a decision resolves all of them at once. */
    val eventId: String = "",
    val userId: String,
    /** Publishing product (`home`, `trading`); reads are always filtered by it. */
    val source: String,
    val type: String,
    val direction: NotificationDirection,
    val title: String,
    val body: String? = null,
    /** Relative deep link (`/notifications`, `/mobile.html#/order/1`). Validated before insert. */
    val url: String? = null,
    val scopeType: String? = null,
    val scopeId: String? = null,
    val scopeName: String? = null,
    val actorUserId: String? = null,
    val actorName: String? = null,
    val targetName: String? = null,
    val actionStatus: NotificationActionStatus = NotificationActionStatus.NONE,
    val actionAt: Long? = null,
    val actionBy: String? = null,
    val readAt: Long? = null,
    val createdAt: Long = 0,
    val expiresAt: Long? = null,
    /** Re-publishing the same logical event for the same recipient is then a no-op. */
    val dedupeKey: String? = null,
    /** JSON for the action handler; opaque to storage. */
    val payload: String? = null,
)

/** Badge counts for one account's inbox, all scoped to the same source. */
data class NotificationSummary(
    /** Incoming or informational items this account has not opened. */
    val unread: Int,
    /** Incoming decisions waiting on this account (and not past their deadline). */
    val actionable: Int,
    /** Decisions this account raised that the other side has not answered yet. */
    val outgoing: Int,
    /**
     * What the account still has to deal with: [unread] and live incoming decisions, **without
     * counting an unread request twice**. This is the honest number for a single badge; adding
     * [unread] and [actionable] would double-count every request the user has not opened yet.
     */
    val attention: Int,
)

/** Keyset cursor: the last row of the previous page, ordered by `(createdAt, id)` descending. */
data class NotificationCursor(
    val createdAt: Long,
    val id: String,
) {
    /** Opaque to the client: it only ever echoes back what the previous page handed it. */
    fun encode(): String = "$createdAt$SEPARATOR$id"

    companion object {
        const val SEPARATOR = "~"

        /** Inverse of [encode]; `null` for a malformed value, so a stale link falls back to page one. */
        @JvmStatic
        fun parse(value: String?): NotificationCursor? {
            if (value.isNullOrBlank()) return null
            val separator = value.lastIndexOf(SEPARATOR)
            if (separator <= 0 || separator == value.length - 1) return null
            val createdAt = value.substring(0, separator).toLongOrNull() ?: return null
            return NotificationCursor(createdAt, value.substring(separator + 1))
        }
    }
}

/**
 * The recipient-granular inbox behind the shared notification page.
 *
 * Nothing here knows about families, orders or agents: the publisher resolves its own business
 * recipients to login account ids and hands over one row per recipient. That is the whole contract,
 * and it is why home and trading can share one table and one page.
 */
interface NotificationStore {

    /**
     * Inserts the given copies in one transaction.
     *
     * Rows whose `dedupeKey` already exists for that recipient are skipped; a race that slips past
     * the check surfaces as a unique-constraint failure and is the caller's to translate. Because
     * the whole call shares one transaction, one conflicting row rolls back **every** row of the
     * batch — the caller must treat the failure as the batch's outcome, not as one row's.
     *
     * @return the rows actually written, with `id` / `createdAt` filled in
     */
    suspend fun insertBatch(rows: List<Notification>): List<Notification>

    /** Newest first, `limit + 1` fetched so the caller can tell whether another page exists. */
    suspend fun findByUser(
        userId: String,
        source: String,
        before: NotificationCursor? = null,
        limit: Int = DEFAULT_LIMIT,
    ): List<Notification>

    suspend fun summary(userId: String, source: String, now: Long = System.currentTimeMillis()): NotificationSummary

    suspend fun findById(id: String): Notification?

    /** Every recipient's copy of one event, used to authorise and to resolve a decision. */
    suspend fun findByEvent(eventId: String): List<Notification>

    /**
     * The still-`PENDING` incoming copy for `(scopeType, scopeId, source, type)`, if any.
     *
     * The deadline is deliberately **not** part of the predicate: an overdue row keeps holding the
     * unique `pendingKey` slot until someone expires it, so the caller has to see it in order to
     * expire it before raising a new request for the same object.
     *
     * The unique `pendingKey` index enforces the one-live-request rule on PostgreSQL and H2 file
     * databases; this lookup is what makes the invariant hold on a fresh H2 in-memory schema, where
     * the caller needs the existing row anyway.
     */
    suspend fun findPendingAction(scopeType: String, scopeId: String, source: String, type: String): Notification?

    /** Scoped by `userId`, so one account can never mark another account's copy as read. */
    suspend fun markRead(userId: String, id: String, readAt: Long): Int

    /** Marks every unread incoming/informational copy of this account. */
    suspend fun markAllRead(userId: String, source: String, readAt: Long): Int

    /**
     * Resolves every still-pending copy of the event.
     *
     * Guarded by `actionStatus = PENDING`: the returned row count is the concurrency gate, so a
     * losing double-click updates nothing instead of running the business side effect twice.
     */
    suspend fun resolveEvent(
        eventId: String,
        status: NotificationActionStatus,
        byUserId: String,
        actionAt: Long,
    ): Int

    /**
     * Lazy expiry of one overdue decision, applied to **every** copy of the event.
     *
     * Guarded, because a concurrent answer may have won; `actionBy` stays unset — nobody acted,
     * the clock did.
     */
    suspend fun expireEvent(eventId: String, actionAt: Long): Int

    companion object {
        const val DEFAULT_LIMIT = 50
    }
}
