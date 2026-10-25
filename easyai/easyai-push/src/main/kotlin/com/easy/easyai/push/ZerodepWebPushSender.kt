package com.easy.easyai.push

import com.easy.easyai.repository.push.PushSubscription
import com.zerodeplibs.webpush.PushSubscription as LibPushSubscription
import com.zerodeplibs.webpush.VAPIDKeyPair
import com.zerodeplibs.webpush.VAPIDKeyPairs
import com.zerodeplibs.webpush.httpclient.StandardHttpClientRequestPreparer
import com.zerodeplibs.webpush.key.PrivateKeySources
import com.zerodeplibs.webpush.key.PublicKeySources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.future.await
import org.slf4j.LoggerFactory
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpResponse
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * VAPID / aes128gcm implementation on top of `zerodep-web-push-java`.
 *
 * Key loading is lazy and *soft*: a missing/invalid key pair only disables the channel (a WARN
 * in the server log, `config().enabled = false` in the API) — it never blocks start-up, and the
 * reason handed to the frontend never contains a filesystem path.
 *
 * The endpoint is re-checked against the host allowlist at send time as well, so a row that
 * predates a configuration tightening cannot be used to reach an arbitrary host.
 */
class ZerodepWebPushSender(
    private val properties: PushProperties,
) : WebPushSender {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

    /** Loaded once; [loadKeyPair] returns null instead of throwing. */
    private val keyPair: VAPIDKeyPair? by lazy { loadKeyPair() }

    override fun isConfigured(): Boolean = keyPair != null

    override fun publicKey(): String? = keyPair?.extractPublicKeyInUncompressedFormAsString()

    override suspend fun send(subscription: PushSubscription, payloadJson: String): PushSendResult {
        val pair = keyPair ?: return PushSendResult.Failed(null, "not-configured")
        PushEndpointRules.endpointProblem(subscription.endpoint, properties.allowedHosts)?.let {
            return PushSendResult.Failed(null, "endpoint-rejected")
        }
        return try {
            val request = StandardHttpClientRequestPreparer.getBuilder()
                .pushSubscription(
                    LibPushSubscription().apply {
                        endpoint = subscription.endpoint
                        keys = LibPushSubscription.Keys().apply {
                            p256dh = subscription.p256dh
                            auth = subscription.auth
                        }
                    },
                )
                .pushMessage(payloadJson)
                .vapidJWTSubject(properties.subject.trim())
                .vapidJWTExpiresAfter(15, TimeUnit.MINUTES)
                .ttl(properties.ttlSeconds, TimeUnit.SECONDS)
                .urgencyNormal()
                .build(pair)
                .toRequestBuilder()
                .timeout(Duration.ofSeconds(10))
                .build()

            // The request carries its own 10s timeout; `await` keeps the call cancellable, so
            // a batch deadline can actually stop the exchange.
            val response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString()).await()
            val host = runCatching { URI(subscription.endpoint).host }.getOrNull()
            when (response.statusCode()) {
                in 200..299 -> {
                    logger.debug("Push sent to subscription {} ({})", subscription.id, host)
                    PushSendResult.Sent
                }

                404, 410 -> {
                    logger.info("Push subscription {} is gone ({})", subscription.id, host)
                    PushSendResult.Gone
                }

                else -> {
                    logger.warn(
                        "Push rejected for subscription {} ({}): status={}",
                        subscription.id,
                        host,
                        response.statusCode(),
                    )
                    PushSendResult.Failed(response.statusCode(), response.body().take(200))
                }
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            PushSendResult.Failed(null, error.javaClass.simpleName)
        }
    }

    private fun loadKeyPair(): VAPIDKeyPair? {
        if (!properties.enabled) return null
        val privatePath = properties.vapidPrivateKeyPath.trim()
        val publicPath = properties.vapidPublicKeyPath.trim()
        if (privatePath.isEmpty() || publicPath.isEmpty()) {
            logger.warn("Web Push disabled: easyai.push.vapid-private-key-path / vapid-public-key-path are empty")
            return null
        }
        if (!subjectLooksValid(properties.subject)) {
            logger.warn("Web Push disabled: easyai.push.subject must start with mailto: or https:")
            return null
        }
        return runCatching {
            VAPIDKeyPairs.of(
                PrivateKeySources.ofPEMFile(Path.of(privatePath)),
                PublicKeySources.ofPEMFile(Path.of(publicPath)),
            )
        }.getOrElse { error ->
            // The path belongs in the server log only — never in the API response.
            logger.warn(
                "Web Push disabled: cannot load VAPID keys from {} and {} ({}: {})",
                privatePath,
                publicPath,
                error.javaClass.simpleName,
                error.message,
            )
            null
        }
    }

    private fun subjectLooksValid(subject: String): Boolean {
        val value = subject.trim()
        return value.startsWith("mailto:", ignoreCase = true) || value.startsWith("https://", ignoreCase = true)
    }
}
