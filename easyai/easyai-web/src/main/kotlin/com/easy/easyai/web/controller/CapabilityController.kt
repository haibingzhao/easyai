package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.StorageSettingsService
import com.easy.easyai.core.storage.StorageSource
import com.easy.easyai.rag.RagConfig
import com.easy.easyai.tools.web.IntegrationConfig
import com.easy.easyai.web.security.currentGroupId
import com.easy.easyai.web.security.currentGroupUserId
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.isGroupOwner
import kotlinx.coroutines.reactor.mono
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * Read-only probe the console calls once after login to decide, per asset kind, whether the group
 * configuration screens are editable or must render read-only.
 *
 * The framework shares every A-class asset (model / aux / mcp / agent / skill / storage) through one
 * group bucket, so the answer is uniform: a member reads the group's assets but may not manage them
 * ([AssetCapability.canManage] mirrors `isGroupOwner`), while the owner may. Two kinds differ:
 *  - **storage** — a deployment-wide `easyai.storage.*` STATIC layer pins the bucket for everyone, so
 *    nobody manages it through the UI and [CapabilitiesDto.setupMode] reports `static`;
 *  - **knowledge** — with `easyai.knowledge.shared-within-group=true` every member reads *and writes*
 *    the same group slice by design (there is no owner gate in `KnowledgeController`), so `canManage`
 *    stays true for all members; when it is off, knowledge is personal and again self-managed.
 *
 * Three kinds are **platform-level**, not group assets: `rag`, `integrations` and `database`. They are
 * deployment-wide file configurations in a single-tenant / desktop deployment (everyone manages their
 * own), but a B/S multi-tenant deployment pins them from Spring properties (`easyai.rag.*`,
 * `easyai.integrations.*`, `easyai.r2dbc.*`). When pinned, `canManage` is false for every login — the
 * console hides the form and the write endpoints refuse — because these settings affect the whole
 * instance and must not be editable by an ordinary family member.
 *
 * Group-less deployments (desktop / CLI / trading) get `groupId = null` and `canManage = true`, which
 * is exactly the pre-group behavior: everyone manages their own assets.
 */
@RestController
@RequestMapping("/api/capabilities")
class CapabilityController(
    @param:Autowired(required = false)
    private val storageSettingsService: StorageSettingsService? = null,
    @param:Value("\${easyai.knowledge.shared-within-group:false}")
    private val knowledgeSharedWithinGroup: Boolean = false,
    /** Which layer pinned the database: `file` (editable) or `spring` (deployment-wide, read-only). */
    @param:Value("\${easyai.database.source:file}")
    private val databaseSource: String = "file"
) {

    @GetMapping
    fun capabilities(): Mono<CapabilitiesDto> = mono {
        val groupId = currentGroupId()
        val groupUserId = currentGroupUserId()
        val owner = isGroupOwner()
        val storageStatic = storageSettingsService?.effectiveSource(currentOwners()) == StorageSource.STATIC

        // Without a group there is nothing shared to gate: every user manages their own assets.
        val groupManaged = groupId != null
        val canManageGroup = !groupManaged || owner
        // Knowledge is shared-writable: members write the group slice too, so it is never owner-gated.
        val knowledgeShared = groupManaged && knowledgeSharedWithinGroup

        // Platform-level kinds: manageable only while they are file-driven, not pinned by properties.
        val ragStatic = RagConfig.isStaticOverridden()
        val integrationsStatic = IntegrationConfig.isStaticOverridden()
        val databaseStatic = databaseSource == "spring"

        val assets = ASSET_KINDS.associateWith { kind ->
            when {
                kind == KIND_STORAGE && storageStatic ->
                    AssetCapability(canRead = true, canManage = false, ownerId = null)
                kind == KIND_KNOWLEDGE ->
                    AssetCapability(canRead = true, canManage = true, ownerId = if (knowledgeShared) groupUserId else null)
                else ->
                    AssetCapability(
                        canRead = true,
                        canManage = canManageGroup,
                        ownerId = if (groupManaged) groupUserId else null
                    )
            }
        } + mapOf(
            KIND_RAG to AssetCapability(canRead = true, canManage = !ragStatic, ownerId = null),
            KIND_INTEGRATIONS to AssetCapability(canRead = true, canManage = !integrationsStatic, ownerId = null),
            KIND_DATABASE to AssetCapability(canRead = true, canManage = !databaseStatic, ownerId = null)
        )

        CapabilitiesDto(
            groupId = groupId,
            groupUserId = if (groupManaged) groupUserId else null,
            isGroupOwner = owner,
            setupMode = if (storageStatic) SETUP_STATIC else SETUP_DATABASE,
            assets = assets
        )
    }

    private companion object {
        val ASSET_KINDS = listOf("model", "aux", "mcp", "agent", "skill", "storage", "knowledge")
        const val KIND_STORAGE = "storage"
        const val KIND_KNOWLEDGE = "knowledge"
        const val KIND_RAG = "rag"
        const val KIND_INTEGRATIONS = "integrations"
        const val KIND_DATABASE = "database"
        const val SETUP_STATIC = "static"
        const val SETUP_DATABASE = "database"
    }
}

/** Whether the current login may read and/or manage one asset kind's group bucket. */
data class AssetCapability(
    val canRead: Boolean,
    val canManage: Boolean,
    /** The shared bucket id assets are written under, or null when group-less / deployment-managed. */
    val ownerId: String?
)

/** The capability snapshot the console caches for the session. */
data class CapabilitiesDto(
    val groupId: String?,
    val groupUserId: String?,
    val isGroupOwner: Boolean,
    /** `static` when a deployment-wide storage layer is pinned, otherwise `database`. */
    val setupMode: String,
    val assets: Map<String, AssetCapability>
)
