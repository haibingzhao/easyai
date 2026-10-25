package com.easy.easyai.notification

import com.easy.easyai.push.PushMessage
import com.easy.easyai.push.PushNotifier
import com.easy.easyai.repository.notification.Notification
import com.easy.easyai.repository.notification.NotificationActionStatus
import com.easy.easyai.repository.notification.NotificationCursor
import com.easy.easyai.repository.notification.NotificationDirection
import com.easy.easyai.repository.notification.NotificationStore
import com.easy.easyai.repository.notification.NotificationSummary
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * The account-granular inbox shared by EasyAI applications.
 *
 * Responsibilities are split the same way home split them: this class owns the generic lifecycle —
 * who may answer, the state transition, lazy expiry, the badge counts — and the
 * [NotificationActionHandler] registered for an event's type owns the business rule.
 *
 * The framework knows nothing about families, orders or agents. A host resolves its own recipients
 * to login account ids and calls [notify] or [request]; each call becomes one row per recipient, and
 * that is the whole contract. In particular **the publisher decides who receives**, which is what
 * makes a cross-scope event (a member of family A inviting a member of family B) just another row.
 *
 * Nothing here is annotated with a host's resource guard, and neither should its controller be: an
 * inbox is inherently cross-scope, so authorization is the caller owning the row they act on.
 */
class NotificationService(
    private val store: NotificationStore,
    private val properties: NotificationProperties,
    private val push: PushNotifier?,
    handlers: List<NotificationActionHandler> = emptyList(),
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Keyed by the stored type name, so an event whose handler was removed in a later build answers
     * with a clear 400 instead of a lookup failure.
     */
    private val handlerByType: Map<String, NotificationActionHandler> = handlers.associateBy { it.type }

    init {
        val duplicated = handlers.groupBy { it.type }.filterValues { it.size > 1 }.keys
        if (duplicated.isNotEmpty()) {
            logger.warn("Duplicate notification handlers for types {}; the last registration wins", duplicated)
        }
        if (properties.source == NotificationProperties.DEFAULT_SOURCE) {
            logger.warn(
                "easyai.notification.source is left at its default '{}'; two applications sharing one " +
                    "database with the same source will read each other's inboxes",
                NotificationProperties.DEFAULT_SOURCE,
            )
        }
    }

    // ==================== publish ====================

    /**
     * Publishes an informational event: every recipient gets their own unread row, so one member
     * reading it does not clear it for the others.
     *
     * @return the rows written — empty when the event addressed no account
     */
    suspend fun notify(recipients: List<String>, event: NotificationEvent): List<Notification> {
        requirePublishable(event)
        val targets = cleanRecipients(recipients, event.actorUserId)
        if (targets.isEmpty()) return emptyList()
        targets.forEach { requireLength("通知接收者", it, USER_ID_MAX_CHARS) }
        val eventId = newEventId()
        return publish(
            targets.map { userId ->
                rowOf(event, eventId, userId, NotificationDirection.INFO, NotificationActionStatus.NONE)
            },
        )
    }

    /**
     * Raises a decision, which is **two** copies of one event: an incoming row the target can answer
     * and an outgoing row the initiator can withdraw.
     *
     * Both copies share the event id, so one answer settles both. Only the incoming copy takes the
     * one-live-request slot — otherwise the initiator's own row would block a second attempt.
     */
    suspend fun request(targetUserId: String, event: NotificationEvent): List<Notification> {
        requirePublishable(event)
        val target = targetUserId.trim()
        if (target.isEmpty()) throw NotificationValidationException("通知接收者不能为空")
        requireLength("通知接收者", target, USER_ID_MAX_CHARS)
        val scopeType = event.scopeType?.trim()?.ifBlank { null }
        val scopeId = event.scopeId?.trim()?.ifBlank { null }
        if ((scopeType == null) != (scopeId == null)) {
            throw NotificationValidationException("scopeType 与 scopeId 必须成对提供")
        }
        if (scopeType != null && scopeId != null) {
            val type = event.type.trim()
            val now = System.currentTimeMillis()
            val pending = store.findPendingAction(scopeType, scopeId, properties.source, type)
            if (pending != null) {
                val deadline = pending.expiresAt
                if (deadline == null || deadline > now) {
                    throw NotificationConflictException("已有待处理的同类型请求，请先撤回或等待对方处理")
                }
                // A lazy-expired row still holds the unique pending slot. Expire the whole event
                // (both copies) before inserting, or the new request fails on that index forever.
                store.expireEvent(pending.eventId, now)
            }
        }

        val eventId = newEventId()
        val copies = mutableListOf(
            rowOf(event, eventId, target, NotificationDirection.INCOMING, NotificationActionStatus.PENDING),
        )
        val actor = event.actorUserId?.trim()?.takeIf { it.isNotEmpty() && it != target }
        if (actor != null) {
            copies += rowOf(event, eventId, actor, NotificationDirection.OUTGOING, NotificationActionStatus.PENDING)
        }
        return publish(copies)
    }

    /**
     * Writes the copies, then hands the device notification off.
     *
     * The hand-off is deliberately outside the failure path: the rows are already saved, so a broken
     * push channel must not turn a successful business write into a 500 for the initiator.
     */
    private suspend fun publish(copies: List<Notification>): List<Notification> {
        val written = try {
            store.insertBatch(copies)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            if (!isUniqueViolation(error)) throw error
            // The pre-check above covers the normal path; this is the race. A lost duplicate of an
            // action is a conflict the user must see, a lost duplicate of a broadcast is a no-op.
            val actionable = copies.any { it.direction == NotificationDirection.INCOMING }
            if (!actionable) {
                logger.warn("Notification event already published for source {} type {}", properties.source, copies.first().type)
                return emptyList()
            }
            throw NotificationConflictException("已有待处理的同类型请求，请先撤回或等待对方处理")
        }
        written.forEach { pushSafely(it) }
        return written
    }

    private fun requirePublishable(event: NotificationEvent) {
        val type = event.type.trim()
        if (type.isEmpty()) throw NotificationValidationException("通知类型不能为空")
        requireLength("通知类型", type, TYPE_MAX_CHARS)
        if (event.title.isBlank()) throw NotificationValidationException("通知标题不能为空")
        event.scopeType?.trim()?.ifBlank { null }?.let { requireLength("scopeType", it, SCOPE_TYPE_MAX_CHARS) }
        event.scopeId?.trim()?.ifBlank { null }?.let { requireLength("scopeId", it, SCOPE_ID_MAX_CHARS) }
        event.actorUserId?.trim()?.ifBlank { null }?.let { requireLength("发起人账号", it, USER_ID_MAX_CHARS) }
        urlProblem(event.url)?.let { throw NotificationValidationException(it) }
    }

    /**
     * Identifiers are compared by exact equality: truncating one would silently point at a different
     * object, so an over-wide value is a caller bug to reject rather than a column to fill.
     */
    private fun requireLength(label: String, value: String, max: Int) {
        if (value.length > max) throw NotificationValidationException("$label 过长（最多 $max 个字符）")
    }

    // ==================== read ====================

    /** The caller's inbox, newest first, within this deployment's source. */
    suspend fun inbox(userId: String, cursor: String? = null, limit: Int? = null): NotificationPage {
        val now = System.currentTimeMillis()
        // The HTTP path passes the raw query parameter through: this is the one place the configured
        // default applies, so `easyai.notification.list-limit` is not shadowed by the controller.
        val size = (limit ?: properties.listLimit).coerceIn(1, MAX_PAGE_SIZE)
        // One row beyond the page is what tells "there is another page" from "this was the end".
        val fetched = store.findByUser(userId, properties.source, NotificationCursor.parse(cursor), size + 1)
        val page = fetched.take(size)
        val settled = page.map { materialize(it, now) }
        val last = settled.lastOrNull()
        return NotificationPage(
            items = settled.map { it.toView(now) },
            nextCursor = if (fetched.size > size && last != null) NotificationCursor(last.createdAt, last.id).encode() else null,
            summary = store.summary(userId, properties.source, now),
        )
    }

    suspend fun summary(userId: String): NotificationSummary =
        store.summary(userId, properties.source, System.currentTimeMillis())

    /**
     * Opens one of the caller's own copies.
     *
     * Read state is separate from the decision state on purpose: opening a request does not answer
     * it, so the avatar keeps its dot until the user actually accepts or rejects.
     */
    suspend fun markRead(userId: String, id: String): NotificationView {
        val row = ownCopy(userId, id)
        val now = System.currentTimeMillis()
        val settled = materialize(row, now)
        if (settled.readAt == null) store.markRead(userId, id, now)
        return settled.copy(readAt = settled.readAt ?: now).toView(now)
    }

    /** Clears unread without settling anything: outgoing copies are left untouched. */
    suspend fun markAllRead(userId: String): Int =
        store.markAllRead(userId, properties.source, System.currentTimeMillis())

    // ==================== respond ====================

    /**
     * Applies a decision.
     *
     * Order matters: materialise (lazy expiry) → check the caller's side → check the state → handler
     * validation → persist the transition → run the handler's side effect. The transition is the
     * concurrency gate — it only writes rows still `PENDING` — so a losing double-click updates
     * nothing and never runs the business side effect twice.
     */
    suspend fun respond(userId: String, id: String, decision: NotificationDecision): NotificationView {
        val row = ownCopy(userId, id)
        val now = System.currentTimeMillis()
        val settled = materialize(row, now)

        when (decision) {
            NotificationDecision.ACCEPT, NotificationDecision.REJECT ->
                if (settled.direction != NotificationDirection.INCOMING) {
                    throw NotificationForbiddenException("该请求不是发给你的")
                }

            NotificationDecision.WITHDRAW ->
                if (settled.direction != NotificationDirection.OUTGOING) {
                    throw NotificationForbiddenException("只有发起人可以撤回该请求")
                }
        }

        if (settled.actionStatus != NotificationActionStatus.PENDING) {
            if (settled.actionStatus == NotificationActionStatus.EXPIRED) {
                throw NotificationValidationException("该请求已过期")
            }
            throw NotificationConflictException("该请求已处理（${settled.actionStatus}）")
        }

        // Withdrawing only flips the state, so a type removed from a later build must stay
        // cancelable by its initiator instead of waiting out its deadline.
        val handler = handlerByType[settled.type]
        if (handler == null && decision != NotificationDecision.WITHDRAW) {
            throw NotificationValidationException("暂不支持的通知类型：${settled.type}")
        }
        val context =
            if (decision == NotificationDecision.ACCEPT) {
                requireNotNull(handler).validate(settled, userId)
            } else {
                null
            }
        val status = when (decision) {
            NotificationDecision.ACCEPT -> NotificationActionStatus.ACCEPTED
            NotificationDecision.REJECT -> NotificationActionStatus.REJECTED
            NotificationDecision.WITHDRAW -> NotificationActionStatus.CANCELLED
        }
        val updated = store.resolveEvent(settled.eventId, status, userId, now)
        if (updated == 0) throw NotificationConflictException("该请求已被处理，请刷新后重试")

        // Answering is reading: the decider has clearly seen the item.
        store.markRead(userId, id, now)

        when (decision) {
            NotificationDecision.ACCEPT -> requireNotNull(handler).onAccepted(settled, context)
            NotificationDecision.REJECT -> requireNotNull(handler).onRejected(settled, context)
            NotificationDecision.WITHDRAW -> handler?.onWithdrawn(settled, context)
        }
        logger.info(
            "Notification event {} ({}) -> {} by user {}",
            settled.eventId, settled.type, status, userId,
        )
        return settled.copy(
            actionStatus = status,
            actionAt = now,
            actionBy = userId,
            readAt = settled.readAt ?: now,
        ).toView(now)
    }

    // ==================== internals ====================

    /** A copy is only ever addressable by its own recipient, inside this deployment's source. */
    private suspend fun ownCopy(userId: String, id: String): Notification {
        val row = store.findById(id) ?: throw NotificationNotFoundException("通知不存在：$id")
        if (row.userId != userId || row.source != properties.source) {
            throw NotificationNotFoundException("通知不存在：$id")
        }
        return row
    }

    /** Flips an overdue decision to `EXPIRED` for every copy, lazily, on read. */
    private suspend fun materialize(row: Notification, now: Long): Notification {
        if (row.actionStatus != NotificationActionStatus.PENDING) return row
        val expiresAt = row.expiresAt ?: return row
        if (expiresAt > now) return row
        if (store.expireEvent(row.eventId, now) == 0) {
            // A concurrent answer landed between the read and here. Report what actually happened
            // instead of claiming the event expired, or `respond` would reject it as overdue.
            return store.findById(row.id) ?: row
        }
        logger.info("Notification event {} ({}) expired", row.eventId, row.type)
        return row.copy(actionStatus = NotificationActionStatus.EXPIRED, actionAt = now)
    }

    private fun cleanRecipients(recipients: List<String>, actorUserId: String?): List<String> {
        val actor = actorUserId?.trim()
        return recipients.map { it.trim() }.filter { it.isNotEmpty() && it != actor }.distinct()
    }

    /**
     * One id per publish call, stamped on **every** copy before the store sees them.
     *
     * Generating it here rather than letting each row fall back to its own id is what makes a
     * broadcast and a two-sided request addressable as one thing — the answer resolves by event id,
     * and the device banner replaces itself by the same key.
     */
    private fun newEventId(): String = UUID.randomUUID().toString()

    private fun rowOf(
        event: NotificationEvent,
        eventId: String,
        userId: String,
        direction: NotificationDirection,
        status: NotificationActionStatus,
    ) = Notification(
        eventId = eventId,
        userId = userId,
        source = properties.source,
        type = event.type.trim(),
        direction = direction,
        title = event.title.trim().take(TITLE_MAX_CHARS),
        body = event.body?.trim()?.ifBlank { null }?.take(BODY_MAX_CHARS),
        url = event.url?.trim()?.ifBlank { null },
        scopeType = event.scopeType?.trim()?.ifBlank { null },
        scopeId = event.scopeId?.trim()?.ifBlank { null },
        scopeName = event.scopeName?.trim()?.ifBlank { null }?.take(NAME_MAX_CHARS),
        actorUserId = event.actorUserId?.trim()?.ifBlank { null },
        actorName = event.actorName?.trim()?.ifBlank { null }?.take(NAME_MAX_CHARS),
        targetName = event.targetName?.trim()?.ifBlank { null }?.take(NAME_MAX_CHARS),
        actionStatus = status,
        // A deadline on an informational row would only make it render as an expiring thing.
        expiresAt = if (status == NotificationActionStatus.PENDING) event.expiresAt else null,
        dedupeKey = event.dedupeKey?.trim()?.ifBlank { null }?.take(DEDUPE_KEY_MAX_CHARS),
        payload = event.payload,
    )

    private fun pushSafely(row: Notification) {
        val notifier = push ?: return
        if (!properties.pushEnabled) return
        // The initiator does not need a banner for the message they just sent, and a broadcast is
        // addressed to everyone else.
        if (row.direction == NotificationDirection.OUTGOING) return
        try {
            if (!notifier.isEnabled()) return
            notifier.enqueue(
                row.userId,
                PushMessage(
                    title = row.title,
                    body = row.body,
                    url = row.url,
                    tag = row.eventId,
                    type = row.type,
                    icon = properties.pushIcon,
                ),
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            logger.warn("Push enqueue failed for notification {}: {}", row.id, error.javaClass.simpleName)
        }
    }

    /**
     * Deep links are opened by the client with no further checks, so only a same-site absolute path
     * is accepted: no scheme, no protocol-relative host, no line break that a proxy could split on.
     */
    private fun urlProblem(raw: String?): String? {
        val url = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (url.length > URL_MAX_CHARS) return "通知链接过长（最多 $URL_MAX_CHARS 个字符）"
        if (!url.startsWith("/")) return "通知链接必须是本站相对路径：$url"
        if (url.startsWith("//") || url.startsWith("/\\") || url.startsWith("\\")) {
            return "通知链接不能指向其他站点：$url"
        }
        if (url.any { it == '\n' || it == '\r' }) return "通知链接包含非法字符"
        return null
    }

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message ?: ""
            if (message.contains("uk_notification_") ||
                message.contains("23505") ||
                message.contains("Unique index or primary key violation")
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private companion object {
        const val MAX_PAGE_SIZE = 200
        const val TYPE_MAX_CHARS = 50
        const val SCOPE_TYPE_MAX_CHARS = 50
        const val SCOPE_ID_MAX_CHARS = 64
        const val USER_ID_MAX_CHARS = 64
        const val TITLE_MAX_CHARS = 200
        const val BODY_MAX_CHARS = 1000
        const val NAME_MAX_CHARS = 200
        const val URL_MAX_CHARS = 500
        const val DEDUPE_KEY_MAX_CHARS = 128
    }
}
