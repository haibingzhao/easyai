package com.easy.easyai.notification

import com.easy.easyai.repository.notification.Notification
import com.easy.easyai.repository.notification.NotificationActionStatus
import com.easy.easyai.repository.notification.NotificationDirection
import com.easy.easyai.repository.notification.NotificationSummary

/** What the caller of `respond` decided to do with an event. */
enum class NotificationDecision { ACCEPT, REJECT, WITHDRAW }

/**
 * One business event, before it is fanned out into per-recipient rows.
 *
 * The display fields ([scopeName], [actorName], [targetName]) are snapshots the publisher resolves
 * from **its own** tables: the framework never joins a business table, which is exactly what lets
 * one inbox and one page serve both a family invite and a trade order.
 */
data class NotificationEvent(
    /** Business key, matched against [NotificationActionHandler.type]. Part of the stored contract. */
    val type: String,
    val title: String,
    val body: String? = null,
    /**
     * Relative deep link the client opens, e.g. `/notifications` or `/mobile.html#/order/1`.
     * An absolute or protocol-relative URL is rejected — the value reaches `window.location` and the
     * Service Worker verbatim.
     */
    val url: String? = null,
    /** The object the event is about (`family`, `order`, `task`), used for the one-live-request rule. */
    val scopeType: String? = null,
    val scopeId: String? = null,
    val scopeName: String? = null,
    /** Who raised the event, for the copy that waits on the other side. */
    val actorUserId: String? = null,
    val actorName: String? = null,
    val targetName: String? = null,
    /** Deadline of a decision; ignored for informational events. */
    val expiresAt: Long? = null,
    /** Repeat publishes of the same logical event land once per recipient. */
    val dedupeKey: String? = null,
    /** JSON for the handler, opaque to the framework. */
    val payload: String? = null,
)

/**
 * A notification with everything the UI needs, including the caller-specific flags.
 *
 * `canRespond` / `canWithdraw` / `unread` are computed server-side: deriving them in the frontend
 * would duplicate the direction rules on the client and let a crafted request skip them anyway.
 */
data class NotificationView(
    val id: String,
    val eventId: String,
    val source: String,
    val type: String,
    val direction: NotificationDirection,
    val actionStatus: NotificationActionStatus,
    val title: String,
    val body: String?,
    val url: String?,
    val scopeName: String?,
    val actorName: String?,
    val targetName: String?,
    val createdAt: Long,
    val expiresAt: Long?,
    val actionAt: Long?,
    val readAt: Long?,
    val canRespond: Boolean,
    val canWithdraw: Boolean,
    val unread: Boolean,
)

/** One page of the inbox, its continuation cursor, and the badges that go with it. */
data class NotificationPage(
    val items: List<NotificationView>,
    val nextCursor: String?,
    val summary: NotificationSummary,
)

/** Structural problem in a publish call (HTTP 400). */
class NotificationValidationException(message: String) : RuntimeException(message)

/** The caller is not the recipient of that copy (HTTP 403). */
class NotificationForbiddenException(message: String) : RuntimeException(message)

/** No such row (HTTP 404). */
class NotificationNotFoundException(message: String) : RuntimeException(message)

/** A live request already exists, or the event was already settled (HTTP 409). */
class NotificationConflictException(message: String) : RuntimeException(message)

/**
 * Converts the store row into the API shape.
 *
 * `unread` deliberately excludes [NotificationDirection.OUTGOING]: the initiator wrote the text and
 * has nothing to open, so counting their own request as unread would light the avatar dot on the
 * side that is only waiting.
 */
internal fun Notification.toView(now: Long): NotificationView {
    val deadline = expiresAt
    val pending = actionStatus == NotificationActionStatus.PENDING && (deadline == null || deadline > now)
    return NotificationView(
        id = id,
        eventId = eventId,
        source = source,
        type = type,
        direction = direction,
        actionStatus = actionStatus,
        title = title,
        body = body,
        url = url,
        scopeName = scopeName,
        actorName = actorName,
        targetName = targetName,
        createdAt = createdAt,
        expiresAt = expiresAt,
        actionAt = actionAt,
        readAt = readAt,
        canRespond = pending && direction == NotificationDirection.INCOMING,
        canWithdraw = pending && direction == NotificationDirection.OUTGOING,
        unread = readAt == null && direction != NotificationDirection.OUTGOING,
    )
}
