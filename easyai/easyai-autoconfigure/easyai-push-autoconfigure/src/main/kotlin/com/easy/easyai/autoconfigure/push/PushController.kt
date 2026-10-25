package com.easy.easyai.autoconfigure.push

import com.easy.easyai.push.PushConfig
import com.easy.easyai.push.PushConflictException
import com.easy.easyai.push.PushTestResult
import com.easy.easyai.push.PushValidationException
import com.easy.easyai.push.WebPushService
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * Account-level Web Push API shared by EasyAI applications.
 *
 * A push subscription belongs to the **login account**, not to any application-level resource
 * (a family, a workspace, …), so every query is scoped to [getCurrentUserId] and the endpoints
 * deliberately take no application-resource id.
 *
 * [serviceWorker] is the one public route: a browser fetches it without an Authorization header.
 * It is served with `Service-Worker-Allowed: /` (the worker must claim the root scope) and
 * `no-store` (a cached worker would make every later fix unreachable).
 */
@RestController
@RequestMapping("/api/push")
class PushController(
    private val push: WebPushService,
) {

    @GetMapping("/config")
    fun config(): Mono<PushConfig> = mono { push.config() }

    @PostMapping("/subscriptions")
    fun subscribe(
        @RequestBody request: PushSubscribeRequest,
        @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) userAgent: String?,
    ): Mono<Map<String, Boolean>> = mono {
        val userId = getCurrentUserId()
        mapped {
            push.subscribe(
                userId = userId,
                endpoint = request.endpoint,
                p256dh = request.keys.p256dh,
                auth = request.keys.auth,
                userAgent = userAgent,
            )
        }
        mapOf("subscribed" to true)
    }

    @PostMapping("/unsubscribe")
    fun unsubscribe(@RequestBody request: PushUnsubscribeRequest): Mono<Map<String, Int>> = mono {
        val removed = push.unsubscribe(getCurrentUserId(), request.endpoint)
        mapOf("removed" to removed)
    }

    @PostMapping("/test")
    fun sendTest(): Mono<PushTestResult> = mono {
        mapped { push.sendTest(getCurrentUserId()) }
    }

    @GetMapping("/sw.js", produces = [SERVICE_WORKER_CONTENT_TYPE])
    fun serviceWorker(): Mono<ResponseEntity<ByteArray>> = mono {
        val script = SERVICE_WORKER_SCRIPT
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Service worker not packaged")
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(SERVICE_WORKER_CONTENT_TYPE))
            .header(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate")
            .header("Service-Worker-Allowed", "/")
            .body(script)
    }

    /** Maps the channel's domain errors onto the API's `{ "error": ... }` contract. */
    private inline fun <T> mapped(block: () -> T): T = try {
        block()
    } catch (error: PushValidationException) {
        throw ResponseStatusException(HttpStatus.BAD_REQUEST, error.message)
    } catch (error: PushConflictException) {
        throw ResponseStatusException(HttpStatus.CONFLICT, error.message)
    }

    private companion object {
        const val SERVICE_WORKER_CONTENT_TYPE = "text/javascript;charset=UTF-8"

        val SERVICE_WORKER_SCRIPT: ByteArray? by lazy {
            PushController::class.java.getResourceAsStream("/push/sw.js")?.use { it.readBytes() }
        }
    }
}

/** Body of `POST /api/push/subscriptions`, mirroring `PushSubscription.toJSON()`. */
data class PushSubscribeRequest(
    val endpoint: String = "",
    val keys: PushKeys = PushKeys(),
)

data class PushKeys(
    val p256dh: String = "",
    val auth: String = "",
)

data class PushUnsubscribeRequest(val endpoint: String = "")
