package com.easy.easyai.push

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.repository.push.PushSubscription
import com.easy.easyai.repository.push.PushSubscriptionStore
import jakarta.annotation.PostConstruct
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * The best-effort Web Push channel shared by EasyAI applications.
 *
 * The application's own database inbox (or equivalent) stays the single source of truth:
 * [enqueue] never throws (so a push failure cannot turn a successful business write into a
 * 500), [deliver] swallows per-device failures, and a dead endpoint is cleaned up on its own
 * 404/410.
 *
 * The service owns one long-lived [CoroutineScope]. Creating a scope per event (the obvious
 * one-liner) leaks a job tree per notification and leaves nothing to cancel at shutdown; the
 * scope here is cancelled in [shutdown] and every send is bounded by the HTTP timeouts.
 *
 * Product glue resolves its own business identifiers (member, device owner, …) to the login
 * `userId` and calls [PushNotifier.enqueue]; nothing business-specific lives here.
 */
class WebPushService(
    private val properties: PushProperties,
    private val sender: WebPushSender,
    private val subscriptions: PushSubscriptionStore,
) : PushNotifier {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val mapper = SharedObjectMapper.instance

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val sendSemaphore = Semaphore(MAX_PARALLEL_SENDS)

    /** Triggers the lazy key load at start-up, so a missing key is visible in the log at boot. */
    @PostConstruct
    fun logConfiguration() {
        // Default-off is the documented state; only an operator who opted in gets a warning.
        if (!properties.enabled) {
            logger.debug("Web Push disabled: easyai.push.enabled=false")
            return
        }
        val config = config()
        if (config.enabled) {
            logger.info("Web Push enabled")
        } else {
            logger.warn("Web Push disabled: {}", config.reason)
        }
    }

    @PreDestroy
    fun shutdown() {
        // `scope.cancel()` is ambiguous after Spring Framework 7 added a `cancel()` of its own;
        // cancelling the context's Job is the unambiguous equivalent.
        scope.coroutineContext.job.cancel()
    }

    fun config(): PushConfig {
        // A negative TTL throws inside zerodep's builder and an empty allowlist rejects every
        // subscribe; without this check both would present as "enabled but silently broken".
        val misconfigured = when {
            properties.ttlSeconds < 0 -> "easyai.push.ttl-seconds 不能为负数"
            properties.allowedHosts.none { it.isNotBlank() } -> "easyai.push.allowed-hosts 不能为空"
            else -> null
        }
        val enabled = properties.enabled && misconfigured == null && sender.isConfigured()
        val reason = when {
            enabled -> null
            !properties.enabled -> "手机推送未启用"
            misconfigured != null -> misconfigured
            else -> "手机推送缺少有效的 VAPID 密钥，请检查服务器配置"
        }
        return PushConfig(
            enabled = enabled,
            publicKey = if (enabled) sender.publicKey() else null,
            reason = reason,
        )
    }

    override fun isEnabled(): Boolean = config().enabled

    /**
     * Registers (or rebinds) one device.
     *
     * Accepted even while the channel is disabled: a subscription is a fact about the device,
     * and keeping it means enabling the server does not require the user to re-grant permission.
     */
    suspend fun subscribe(
        userId: String,
        endpoint: String,
        p256dh: String,
        auth: String,
        userAgent: String?,
    ) {
        PushEndpointRules.endpointProblem(endpoint, properties.allowedHosts)
            ?.let { throw PushValidationException(it) }
        PushEndpointRules.p256dhProblem(p256dh)?.let { throw PushValidationException(it) }
        PushEndpointRules.authProblem(auth)?.let { throw PushValidationException(it) }

        val existing = subscriptions.findByEndpoint(endpoint)
        if (existing?.userId != userId && subscriptions.countByUserId(userId) >= MAX_SUBSCRIPTIONS_PER_USER) {
            throw PushConflictException(
                "该账号的推送设备数已达上限（$MAX_SUBSCRIPTIONS_PER_USER），请先关闭部分设备的通知",
            )
        }
        subscriptions.upsert(
            PushSubscription(
                userId = userId,
                endpoint = endpoint,
                p256dh = p256dh,
                auth = auth,
                userAgent = userAgent?.take(USER_AGENT_MAX_LENGTH),
            ),
        )
    }

    /** Removes the caller's own subscription; idempotent, returns the number of deleted rows. */
    suspend fun unsubscribe(userId: String, endpoint: String): Int =
        subscriptions.deleteByUserAndEndpoint(userId, endpoint)

    /**
     * Fire-and-forget hand-off from a business service. Never throws: the push channel must not
     * be able to fail an event that has already been persisted.
     */
    override fun enqueue(userId: String, message: PushMessage) {
        runCatching {
            if (!config().enabled) return
            scope.launch {
                runCatching { deliver(userId, message) }
                    .onFailure {
                        logger.warn("Push delivery failed for user {}: {}", userId, it.javaClass.simpleName)
                    }
            }
        }.onFailure {
            logger.warn("Push enqueue failed for user {}: {}", userId, it.javaClass.simpleName)
        }
    }

    /**
     * Sends one message to every device of the account.
     *
     * Visible for tests: it is the whole delivery path without the coroutine hop. An account
     * with no devices is not an error for the event itself.
     */
    internal suspend fun deliver(userId: String, message: PushMessage) {
        if (!config().enabled) return
        val devices = subscriptions.findByUserId(userId, MAX_SUBSCRIPTIONS_PER_USER)
        if (devices.isEmpty()) return

        val payload = payload(message)
        val sent = AtomicInteger()
        val gone = AtomicInteger()
        val failed = AtomicInteger()
        coroutineScope {
            devices.forEach { subscription ->
                launch {
                    sendSemaphore.withPermit {
                        try {
                            when (val result = sender.send(subscription, payload)) {
                                PushSendResult.Sent -> sent.incrementAndGet()
                                PushSendResult.Gone -> {
                                    gone.incrementAndGet()
                                    subscriptions.deleteById(subscription.id)
                                }

                                is PushSendResult.Failed -> {
                                    failed.incrementAndGet()
                                    logger.warn(
                                        "Push failed for subscription {} (user {}): status={} reason={}",
                                        subscription.id, userId, result.status, result.reason,
                                    )
                                }
                            }
                        } catch (error: CancellationException) {
                            throw error
                        } catch (error: Exception) {
                            // A custom sender must not take the other devices down with it.
                            failed.incrementAndGet()
                            logger.warn(
                                "Push failed for subscription {} (user {}): {}",
                                subscription.id, userId, error.javaClass.simpleName,
                            )
                        }
                    }
                }
            }
        }
        logger.debug(
            "Push for user {}: sent={}, gone={}, failed={}",
            userId, sent.get(), gone.get(), failed.get(),
        )
    }

    /**
     * Sends a test notification to the caller's devices.
     *
     * Devices are fanned out in parallel with a bounded semaphore, and the whole batch has a
     * hard deadline — twenty devices must not turn one HTTP request into a 200-second wait.
     */
    suspend fun sendTest(userId: String): PushTestResult {
        val config = config()
        if (!config.enabled) throw PushConflictException(config.reason ?: "手机推送未启用")

        val devices = subscriptions.findByUserId(userId, MAX_SUBSCRIPTIONS_PER_USER)
        if (devices.isEmpty()) return PushTestResult(sent = 0, failed = 0, gone = 0)

        val payload = testPayload()
        val results = Collections.synchronizedList(mutableListOf<PushSendResult>())
        val completed = withTimeoutOrNull(SEND_TEST_TIMEOUT_MILLIS) {
            coroutineScope {
                devices.forEach { subscription ->
                    launch {
                        sendSemaphore.withPermit {
                            try {
                                val result = sender.send(subscription, payload)
                                if (result == PushSendResult.Gone) {
                                    subscriptions.deleteById(subscription.id)
                                }
                                results.add(result)
                            } catch (error: CancellationException) {
                                throw error
                            } catch (error: Exception) {
                                results.add(PushSendResult.Failed(null, error.javaClass.simpleName))
                            }
                        }
                    }
                }
            }
            true
        } ?: false
        if (!completed) {
            logger.warn(
                "Push test for user {} hit the {} ms deadline: {} of {} devices attempted",
                userId, SEND_TEST_TIMEOUT_MILLIS, results.size, devices.size,
            )
        }
        return PushTestResult(
            sent = results.count { it == PushSendResult.Sent },
            failed = results.count { it is PushSendResult.Failed },
            gone = results.count { it == PushSendResult.Gone },
            timedOut = !completed,
        )
    }

    // ==================== payload ====================

    private fun payload(message: PushMessage): String {
        val title = message.title.take(TITLE_MAX_CHARS).ifBlank { DEFAULT_TITLE }
        val body = (message.body?.takeIf { it.isNotBlank() } ?: DEFAULT_BODY).take(BODY_MAX_CHARS)
        val url = message.url?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
        val full = payloadJson(title = title, body = body, url = url, message = message)
        if (full.utf8Size() <= MAX_PAYLOAD_BYTES) return logPayload(title, full)

        // Emoji / escaping can push a character-limited body past the encrypted-size ceiling;
        // fall back to the generic line rather than let Apple reject the whole message.
        val compact = payloadJson(title = title, body = DEFAULT_BODY, url = url, message = message)
        if (compact.utf8Size() <= MAX_PAYLOAD_BYTES) return logPayload(title, compact)

        return logPayload(
            title,
            mapper.writeValueAsString(
                linkedMapOf(
                    "title" to DEFAULT_TITLE,
                    "body" to DEFAULT_BODY,
                    "url" to DEFAULT_URL,
                ),
            ),
        )
    }

    private fun payloadJson(
        title: String,
        body: String,
        url: String,
        message: PushMessage,
    ): String {
        val payload = linkedMapOf<String, Any?>("title" to title, "body" to body, "url" to url)
        message.tag?.takeIf { it.isNotBlank() }?.let { payload["notificationId"] = it }
        message.type?.takeIf { it.isNotBlank() }?.let { payload["type"] = it }
        message.icon?.takeIf { it.isNotBlank() }?.let { payload["icon"] = it }
        return mapper.writeValueAsString(payload)
    }

    private fun testPayload(): String = mapper.writeValueAsString(
        linkedMapOf(
            "title" to TEST_TITLE,
            "body" to TEST_BODY,
            "url" to DEFAULT_URL,
            "type" to "TEST",
        ),
    )

    private fun logPayload(title: String, json: String): String {
        logger.debug("Push payload \"{}\" is {} bytes", title, json.utf8Size())
        return json
    }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private companion object {
        const val MAX_SUBSCRIPTIONS_PER_USER = 20
        const val MAX_PARALLEL_SENDS = 8
        const val SEND_TEST_TIMEOUT_MILLIS = 20_000L
        const val USER_AGENT_MAX_LENGTH = 500
        const val TITLE_MAX_CHARS = 200
        const val BODY_MAX_CHARS = 280
        const val MAX_PAYLOAD_BYTES = 3000
        const val DEFAULT_TITLE = "新通知"
        const val DEFAULT_BODY = "你有一条新通知"
        const val DEFAULT_URL = "/"
        const val TEST_TITLE = "测试通知"
        const val TEST_BODY = "如果你看到这条通知，说明推送已配置成功。"
    }
}
