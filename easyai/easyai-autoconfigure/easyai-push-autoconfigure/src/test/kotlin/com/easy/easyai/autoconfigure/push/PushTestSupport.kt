package com.easy.easyai.autoconfigure.push

import com.easy.easyai.push.PushSendResult
import com.easy.easyai.push.WebPushSender
import com.easy.easyai.repository.push.PushSubscription
import com.easy.easyai.repository.push.PushSubscriptionStore
import java.util.Base64
import java.util.Collections
import java.util.UUID

/** Test doubles: no test may reach Apple's push service. */

internal class FakeSender(
    var configured: Boolean = true,
) : WebPushSender {

    var result: PushSendResult = PushSendResult.Sent
    val sent = Collections.synchronizedList(mutableListOf<PushSubscription>())

    override fun isConfigured(): Boolean = configured

    override fun publicKey(): String? = if (configured) PUBLIC_KEY else null

    override suspend fun send(subscription: PushSubscription, payloadJson: String): PushSendResult {
        sent += subscription
        return result
    }

    companion object {
        const val PUBLIC_KEY = "BPublicKey"
    }
}

internal class MemoryStore : PushSubscriptionStore {

    private val rows = linkedMapOf<String, PushSubscription>()

    override suspend fun findByUserId(userId: String, limit: Int): List<PushSubscription> =
        rows.values.filter { it.userId == userId }.sortedByDescending { it.updatedAt }.take(limit)

    override suspend fun findByEndpoint(endpoint: String): PushSubscription? =
        rows.values.firstOrNull { it.endpoint == endpoint }

    override suspend fun countByUserId(userId: String): Int = rows.values.count { it.userId == userId }

    override suspend fun upsert(subscription: PushSubscription) {
        val existing = findByEndpoint(subscription.endpoint)
        val stored = if (existing == null) {
            subscription.copy(id = subscription.id.ifBlank { UUID.randomUUID().toString() })
        } else {
            subscription.copy(id = existing.id, createdAt = existing.createdAt)
        }
        rows[stored.id] = stored
    }

    override suspend fun deleteByUserAndEndpoint(userId: String, endpoint: String): Int {
        val match = rows.values.firstOrNull { it.userId == userId && it.endpoint == endpoint } ?: return 0
        rows.remove(match.id)
        return 1
    }

    override suspend fun deleteById(id: String): Int = if (rows.remove(id) != null) 1 else 0
}

internal const val TEST_ENDPOINT = "https://web.push.apple.com/test-device"

internal fun base64Url(bytes: ByteArray): String =
    Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)

internal val TEST_P256DH: String = base64Url(byteArrayOf(0x04) + ByteArray(64) { (it + 1).toByte() })
internal val TEST_AUTH: String = base64Url(ByteArray(16) { (it + 1).toByte() })
