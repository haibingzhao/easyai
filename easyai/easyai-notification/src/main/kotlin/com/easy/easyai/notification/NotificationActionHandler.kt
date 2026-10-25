package com.easy.easyai.notification

import com.easy.easyai.repository.notification.Notification

/**
 * The business rule behind one actionable notification type.
 *
 * Split deliberately: [NotificationService] owns the generic lifecycle (who may answer, the state
 * transition, lazy expiry), the handler registered for a type owns what accepting actually does.
 * A host adds a notification type by publishing an event with that `type` and registering a handler
 * for it — no framework change.
 *
 * The lifecycle hooks run **after** the row is already marked, so a handler failure cannot leave a
 * second decision being answered on top of a half-applied one. That also means every hook must be
 * idempotent: it is the state transition, not the hook, that is the concurrency gate.
 */
interface NotificationActionHandler {

    /** The stored type name this handler answers. Duplicate registrations are a wiring bug. */
    val type: String

    /**
     * Re-checks the business rule at answer time and returns whatever [onAccepted] needs.
     *
     * Runs before the transition, so throwing here leaves the notification pending. Returning a
     * context is how a handler passes a resolved entity to its own side effect without re-reading.
     */
    suspend fun validate(notification: Notification, responderUserId: String): Any? = null

    suspend fun onAccepted(notification: Notification, context: Any?)

    suspend fun onRejected(notification: Notification, context: Any?) {}

    /** The initiator took the request back. */
    suspend fun onWithdrawn(notification: Notification, context: Any?) {}
}
