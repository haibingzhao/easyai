package com.easy.easyai.web.controller

import com.easy.easyai.core.media.MediaProviderResult
import com.easy.easyai.core.media.MediaProviderService
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * REST controller for per-user media-generation provider credentials (frontend Settings → Media).
 *
 * One row per `(user, serviceKind)` where serviceKind is `speech` / `image` / `video`:
 * - GET  /api/media/providers            - all kinds for the caller, secrets masked, with the layer in force
 * - GET  /api/media/providers/{kind}     - one kind
 * - POST /api/media/providers/{kind}     - validate, persist and hot-apply one kind
 * - POST /api/media/providers/{kind}/test - probe a draft without persisting
 *
 * Credentials never leave the server unmasked: the read path shows a mask, saving a blank secret
 * keeps the stored one, and the owner comes from the security context only — a body-supplied userId
 * could otherwise write under another account. All endpoints answer 503 when persistence is absent
 * (`easyai.r2dbc.enabled=false`); the database is the only place media providers are configured.
 */
@RestController
@RequestMapping("/api/media/providers")
class MediaProviderConfigController(
    @param:Autowired(required = false)
    private val providerService: MediaProviderService? = null
) {

    @GetMapping
    fun listProviders(): Mono<List<MediaProviderDto>> = mono {
        val service = providerService ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        val byKind = service.list(userId).associateBy { it.serviceKind }
        MediaProviderSettings.ALL_SERVICE_KINDS.map { kind ->
            toDto(kind, byKind[kind], service.effectiveSource(userId, kind).name)
        }
    }

    @GetMapping("/{kind}")
    fun getProvider(@PathVariable kind: String): Mono<MediaProviderDto> = mono {
        val service = providerService ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        requireKind(kind)
        toDto(kind, service.current(userId, kind), service.effectiveSource(userId, kind).name)
    }

    /** Save one kind; a valid enabled draft takes effect for the next generation call, no restart. */
    @PostMapping("/{kind}")
    fun saveProvider(
        @PathVariable kind: String,
        @RequestBody request: SaveMediaProviderRequest
    ): Mono<MediaProviderDto> = mono {
        val service = providerService ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        requireKind(kind)
        when (val outcome = service.save(userId, request.toSettings(kind))) {
            is MediaProviderResult.Saved ->
                toDto(kind, outcome.settings, service.effectiveSource(userId, kind).name)

            is MediaProviderResult.Invalid -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, outcome.reason)

            MediaProviderResult.Unavailable -> throw databaseDisabled()
        }
    }

    /** Structural probe of a draft (its blank secret falls back to the stored one); never persists. */
    @PostMapping("/{kind}/test")
    fun testProvider(
        @PathVariable kind: String,
        @RequestBody request: SaveMediaProviderRequest
    ): Mono<MediaProviderTestDto> = mono {
        val service = providerService ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        requireKind(kind)
        val failure = service.probe(userId, request.toSettings(kind))
        if (failure == null) {
            MediaProviderTestDto(success = true, message = "Configuration OK")
        } else {
            MediaProviderTestDto(success = false, message = failure)
        }
    }

    private fun requireKind(kind: String) {
        if (kind !in MediaProviderSettings.ALL_SERVICE_KINDS) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown media service kind '$kind'")
        }
    }

    private fun databaseDisabled(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Media provider settings require the database (set easyai.r2dbc.enabled=true); it is the only place they are configured"
    )

    private fun SaveMediaProviderRequest.toSettings(kind: String): MediaProviderSettings = MediaProviderSettings(
        enabled = enabled ?: false,
        serviceKind = kind,
        providerType = providerType?.takeIf { it.isNotBlank() } ?: MediaProviderSettings.PROVIDER_OPENAI,
        baseUrl = baseUrl?.trim() ?: "",
        region = region?.trim() ?: "",
        apiKey = apiKey ?: "",
        accessKeyId = accessKeyId?.trim() ?: "",
        accessKeySecret = accessKeySecret ?: "",
        defaultModel = defaultModel?.trim() ?: "",
        options = options?.trim() ?: "",
        timeoutSeconds = timeoutSeconds ?: 600L
    )

    /** Null [stored] means "nothing saved yet" — the card opens on the disabled defaults. */
    private fun toDto(kind: String, stored: MediaProviderSettings?, effectiveSource: String): MediaProviderDto =
        MediaProviderDto(
            serviceKind = kind,
            enabled = stored?.enabled ?: false,
            providerType = stored?.providerType ?: MediaProviderSettings.PROVIDER_OPENAI,
            baseUrl = stored?.baseUrl ?: "",
            region = stored?.region ?: "",
            apiKey = maskSecret(stored?.apiKey),
            accessKeyId = stored?.accessKeyId ?: "",
            accessKeySecret = maskSecret(stored?.accessKeySecret),
            defaultModel = stored?.defaultModel ?: "",
            options = stored?.options ?: "",
            timeoutSeconds = stored?.timeoutSeconds ?: 600L,
            effectiveSource = effectiveSource.lowercase()
        )

    private fun maskSecret(secret: String?): String? {
        if (secret.isNullOrBlank()) return null
        if (secret.length <= 8) return "****"
        return secret.take(4) + "****" + secret.takeLast(4)
    }
}

/** Stored configuration for one kind; [apiKey]/[accessKeySecret] are masks, never the value. */
data class MediaProviderDto(
    val serviceKind: String,
    val enabled: Boolean,
    val providerType: String,
    val baseUrl: String,
    val region: String,
    val apiKey: String?,
    val accessKeyId: String,
    val accessKeySecret: String?,
    val defaultModel: String,
    val options: String,
    val timeoutSeconds: Long,
    /** Which layer is in force right now: user | system | none. */
    val effectiveSource: String
)

/** Save draft; a null/blank [apiKey]/[accessKeySecret] keeps the stored secret. */
data class SaveMediaProviderRequest(
    val enabled: Boolean? = null,
    val providerType: String? = null,
    val baseUrl: String? = null,
    val region: String? = null,
    val apiKey: String? = null,
    val accessKeyId: String? = null,
    val accessKeySecret: String? = null,
    val defaultModel: String? = null,
    val options: String? = null,
    val timeoutSeconds: Long? = null
)

/** Outcome of a structural probe against an unsaved draft. */
data class MediaProviderTestDto(
    val success: Boolean,
    val message: String
)
