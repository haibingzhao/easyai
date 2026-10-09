package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.core.storage.StorageSettingsResult
import com.easy.easyai.core.storage.StorageSettingsService
import com.easy.easyai.core.storage.StorageSource
import com.easy.easyai.web.security.AssetScope
import com.easy.easyai.web.security.currentGroupUserId
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.security.parseAssetScope
import com.easy.easyai.web.security.resolveWriteOwner
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * REST controller for the per-user object-storage configuration (frontend Settings → Storage).
 *
 * Endpoints:
 * - GET  /api/storage/config      - The stored row (secret masked) plus the layer in force
 * - POST /api/storage/config      - Validate, persist and hot-apply one configuration
 * - POST /api/storage/config/test - Probe a draft without persisting anything
 *
 * Credentials never leave the server unmasked: the read path shows a mask, saving a blank secret
 * means "keep the stored one", and installs/probes sign URLs server-side. The owner comes from
 * the security context only — a body-supplied userId could write under another account.
 *
 * All three answer 503 when persistence is absent (`easyai.r2dbc.enabled=false`) — the database
 * is the only configuration source, so without it there is nowhere to store a row and
 * storage-dependent features stay off.
 */
@RestController
@RequestMapping("/api/storage")
class StorageConfigController(
    @param:Autowired(required = false)
    private val settingsService: StorageSettingsService? = null
) {

    @GetMapping("/config")
    fun getConfig(@RequestParam(required = false) scope: String? = null): Mono<StorageConfigDto> = mono {
        val service = settingsService ?: throw databaseDisabled()
        val owners = currentOwners()
        if (service.effectiveSource(owners) == StorageSource.STATIC) {
            return@mono staticDto()
        }
        // A group-scoped read must answer with the group's row. Falling back to the caller's own row
        // when the login has no active group would silently report a personal config as the shared one.
        val readOwner = if (parseAssetScope(scope) == AssetScope.GROUP) {
            currentGroupUserId()
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No active group for a group-scoped read")
        } else {
            getCurrentUserId()
        }
        // Report the layer in force for the owner being read, not the caller's whole visibility set:
        // a member viewing the group config must see the group's source, not their own row shadowing it.
        toDto(service.current(readOwner), service.effectiveSource(listOf(readOwner)).name)
    }

    /** Save a configuration; a valid one takes effect for the next storage operation, no restart. */
    @PostMapping("/config")
    fun saveConfig(
        @RequestBody request: SaveStorageConfigRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<StorageConfigDto> = mono {
        val service = settingsService ?: throw databaseDisabled()
        if (service.effectiveSource(currentOwners()) == StorageSource.STATIC) {
            throw ResponseStatusException(
                HttpStatus.FORBIDDEN,
                "Storage is pinned by a deployment-wide easyai.storage.* configuration and cannot be overridden per user"
            )
        }
        val owner = resolveWriteOwner(parseAssetScope(scope))
        when (val outcome = service.save(owner, request.toSettings())) {
            // Report the layer in force for the bucket just written, matching getConfig: a group owner
            // saving the shared config must not be told their own personal row is the one in force.
            is StorageSettingsResult.Saved -> toDto(outcome.settings, service.effectiveSource(listOf(owner)).name)

            is StorageSettingsResult.Invalid -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, outcome.reason)

            StorageSettingsResult.Unavailable -> throw databaseDisabled()
        }
    }

    /** Round-trip a probe object with the draft (its blank secret falls back to the stored one). */
    @PostMapping("/config/test")
    fun testConfig(@RequestBody request: SaveStorageConfigRequest): Mono<StorageTestDto> = mono {
        val service = settingsService ?: throw databaseDisabled()
        val userId = getCurrentUserId()
        val failure = service.probe(userId, request.toSettings())
        if (failure == null) {
            StorageTestDto(success = true, message = "Connection OK")
        } else {
            StorageTestDto(success = false, message = failure)
        }
    }

    private fun databaseDisabled(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Storage settings require the database (set easyai.r2dbc.enabled=true); it is the only place storage is configured"
    )

    private fun SaveStorageConfigRequest.toSettings(): StorageSettings = StorageSettings(
        enabled = enabled ?: false,
        type = type?.takeIf { it.isNotBlank() } ?: StorageSettings.TYPE_ALIYUN,
        endpoint = endpoint?.trim() ?: "",
        bucket = bucket?.trim() ?: "",
        accessKeyId = accessKeyId?.trim() ?: "",
        accessKeySecret = accessKeySecret ?: "",
        localDir = localDir?.trim() ?: ""
    )

    /** Null [stored] means "nothing saved yet" — the form opens on the disabled defaults. */
    private fun toDto(stored: StorageSettings?, effectiveSource: String): StorageConfigDto = StorageConfigDto(
        enabled = stored?.enabled ?: false,
        type = stored?.type ?: StorageSettings.TYPE_ALIYUN,
        endpoint = stored?.endpoint ?: "",
        bucket = stored?.bucket ?: "",
        accessKeyId = stored?.accessKeyId ?: "",
        accessKeySecret = maskSecret(stored?.accessKeySecret),
        localDir = stored?.localDir ?: "",
        effectiveSource = effectiveSource.lowercase()
    )

    /**
     * The STATIC (`easyai.storage.*`) layer is deployment-pinned: report it as in force so the
     * frontend hides the form, but never echo any credential — not even a mask of one.
     */
    private fun staticDto(): StorageConfigDto = StorageConfigDto(
        enabled = true,
        type = "",
        endpoint = "",
        bucket = "",
        accessKeyId = "",
        accessKeySecret = null,
        localDir = "",
        effectiveSource = StorageSource.STATIC.name.lowercase()
    )

    private fun maskSecret(apiKey: String?): String? {
        if (apiKey.isNullOrBlank()) return null
        if (apiKey.length <= 8) return "****"
        return apiKey.take(4) + "****" + apiKey.takeLast(4)
    }
}

/** Stored configuration for the current user; [accessKeySecret] is a mask, never the value. */
data class StorageConfigDto(
    val enabled: Boolean,
    val type: String,
    val endpoint: String,
    val bucket: String,
    val accessKeyId: String,
    val accessKeySecret: String?,
    val localDir: String,
    /** Which layer is in force right now: user | system | static | none. `static` means a
     *  deployment-wide `easyai.storage.*` layer is pinned — the form is hidden and saves refused. */
    val effectiveSource: String
)

/** Save draft; a null/blank [accessKeySecret] keeps the stored secret. */
data class SaveStorageConfigRequest(
    val enabled: Boolean? = null,
    val type: String? = null,
    val endpoint: String? = null,
    val bucket: String? = null,
    val accessKeyId: String? = null,
    val accessKeySecret: String? = null,
    val localDir: String? = null
)

/** Outcome of a connectivity probe against an unsaved draft. */
data class StorageTestDto(
    val success: Boolean,
    val message: String
)
