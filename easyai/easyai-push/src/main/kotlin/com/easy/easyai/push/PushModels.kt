package com.easy.easyai.push

/**
 * One message to show in the device notification centre.
 *
 * [tag] maps to the Service Worker `tag`, so re-delivering the same logical event replaces the
 * previous banner instead of stacking duplicates. [type] is opaque to the channel and only
 * echoed back to the client for its own routing.
 */
data class PushMessage(
    val title: String,
    val body: String? = null,
    val url: String? = null,
    val tag: String? = null,
    val type: String? = null,
    val icon: String? = null,
)

/** What the frontend needs to decide whether "enable notifications" is offered. */
data class PushConfig(
    val enabled: Boolean,
    val publicKey: String?,
    val reason: String?,
)

/** Delivery counters of a synchronous test notification (one per subscribed device). */
data class PushTestResult(
    val sent: Int,
    val failed: Int,
    val gone: Int,
    /** The batch deadline elapsed first, so the counters only cover the attempted devices. */
    val timedOut: Boolean = false,
)

/** Outcome of one delivery attempt to a push service. */
sealed interface PushSendResult {

    /** 2xx — the push service accepted the message for delivery. */
    data object Sent : PushSendResult

    /** 404 / 410 — the subscription no longer exists and its row must be removed. */
    data object Gone : PushSendResult

    /** Anything else; [reason] is a short, non-sensitive description (never the endpoint). */
    data class Failed(val status: Int?, val reason: String) : PushSendResult
}

/** Browser-supplied subscription data that failed a structural check (HTTP 400). */
class PushValidationException(message: String) : RuntimeException(message)

/** The channel is disabled or a hard limit was reached (HTTP 409). */
class PushConflictException(message: String) : RuntimeException(message)
