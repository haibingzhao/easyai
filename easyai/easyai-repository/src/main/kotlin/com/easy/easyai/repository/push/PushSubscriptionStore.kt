package com.easy.easyai.repository.push

/**
 * One browser push endpoint of a login account.
 *
 * `id` / `createdAt` are filled by the store on first insert and preserved by the endpoint
 * upsert, so a rebind does not change the row identity.
 */
data class PushSubscription(
    val id: String = "",
    val userId: String,
    val endpoint: String,
    val p256dh: String,
    val auth: String,
    val userAgent: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
)

/**
 * Account-scoped device registry behind Web Push.
 *
 * The only write path is [upsert] keyed by `endpoint`: the same browser may switch accounts and
 * a device must have exactly one owner, so a re-subscribe rebinds the existing row instead of
 * inserting a second one.
 */
interface PushSubscriptionStore {

    suspend fun findByUserId(userId: String, limit: Int = DEFAULT_LIMIT): List<PushSubscription>

    suspend fun findByEndpoint(endpoint: String): PushSubscription?

    suspend fun countByUserId(userId: String): Int

    /** Inserts or rebinds the row for `subscription.endpoint`. */
    suspend fun upsert(subscription: PushSubscription)

    /** Scoped delete, so one account can never remove another account's device. */
    suspend fun deleteByUserAndEndpoint(userId: String, endpoint: String): Int

    /** Cleanup of a dead endpoint (404/410 from the push service); the row id is enough. */
    suspend fun deleteById(id: String): Int

    companion object {
        const val DEFAULT_LIMIT = 20
    }
}
