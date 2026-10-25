package com.easy.easyai.push

import com.easy.easyai.repository.push.PushSubscription

/**
 * Sends one encrypted Web Push message.
 *
 * Kept as an interface so tests inject a fake and never touch the network, and so the VAPID
 * implementation stays replaceable.
 */
interface WebPushSender {

    /** True when the key pair (and subject) are usable; `config().enabled` is derived from it. */
    fun isConfigured(): Boolean

    /** base64url, unpadded — the value `pushManager.subscribe` expects. Null when unconfigured. */
    fun publicKey(): String?

    suspend fun send(subscription: PushSubscription, payloadJson: String): PushSendResult
}
