package com.easy.easyai.push

/**
 * Narrow, non-suspending hand-off used by business services.
 *
 * Product code only needs "tell this account about this event"; device lookup and encryption
 * stay inside the push module. Implementations must never throw, because the event has already
 * been persisted and a broken push channel must not turn it into a failure.
 */
interface PushNotifier {

    /** False when the switch is off or VAPID keys are missing; callers may skip payload work. */
    fun isEnabled(): Boolean

    fun enqueue(userId: String, message: PushMessage)
}
