package com.easy.easyai.web.controller

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.skills.ScopedSkill
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillAddResult
import com.easy.easyai.skills.SkillCatalogService
import com.easy.easyai.skills.SkillCatalogView
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillRefreshService
import com.easy.easyai.skills.SkillToggleResult
import com.easy.easyai.skills.SkillUpload
import com.easy.easyai.skills.SkillUploadFile
import com.easy.easyai.web.security.currentOwners
import com.easy.easyai.web.security.getCurrentUserId
import com.easy.easyai.web.security.parseAssetScope
import com.easy.easyai.web.security.resolveWriteOwner
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.multipart.FilePart
import org.springframework.http.codec.multipart.Part
import org.springframework.util.MultiValueMap
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.nio.file.Files
import java.nio.file.Path

/**
 * REST surface of the skill subsystem: the merged view of one user's skills, plus install (server
 * directory or browser upload), toggle and delete.
 *
 * Ownership is two-level and never request-supplied: every handler derives the owner from
 * [getCurrentUserId], and the shared `system` layer is writable only by a caller who *is* that owner
 * (the auth-disabled single-machine case). A body-supplied userId would let one user install, toggle
 * or delete under another's name.
 *
 * Both installs go through [SkillRefreshService], so an HTTP request and the `refresh_skills` tool
 * cannot disagree about what a valid SKILL.md is, and both leave catalog row, object-storage package,
 * local directory and retrieval index in the same state.
 *
 * Install outcomes travel as a body rather than as an error status with an unreadable reason: 409
 * means "pick another name", 400 means "this is not a skill", 413 means "too large". The frontend
 * branches on the status and shows [SkillAddResultDto.message].
 */
@RestController
@RequestMapping("/api/skills")
class SkillController(
    @param:Autowired(required = false) private val skillCatalogService: SkillCatalogService? = null,
    @param:Autowired(required = false) private val skillAccessResolver: SkillAccessResolver? = null,
    @param:Autowired(required = false) private val skillRefreshService: SkillRefreshService? = null,
    @param:Autowired(required = false) private val skillCatalog: AsyncSkillCatalogStore? = null,
    @param:Autowired(required = false) private val skillConfig: SkillConfig? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Skills visible to the caller: their own rows plus the shared layer, an own row shadowing a
     * same-named shared one. Content fields come from the installed copy, lifecycle fields from the
     * catalog row — so a skill whose directory is missing still lists, as pending restore.
     */
    @GetMapping
    fun listSkills(): Mono<List<SkillDto>> = mono {
        visibleSkills(currentOwners())
    }

    /**
     * Install from a directory the server can already read — what the directory picker returns.
     * Validation, copying, packaging, claim and indexing all happen in the sync pipeline, so a
     * refused package leaves no row and no directory behind.
     */
    @PostMapping
    fun addFromDirectory(
        @RequestBody request: SkillAddRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<ResponseEntity<SkillAddResultDto>> = mono {
        val service = skillRefreshService ?: throw skillSystemNotEnabled()
        val owner = resolveOwner(request.shared, scope)
        val name = request.name.trim()
        val source = request.sourcePath.trim().takeIf { it.isNotBlank() }?.let { sourceDirectory(it, owner) }
            ?: return@mono badRequest("sourcePath must be an absolute path of a directory that exists on this server")
        respond(owner, name, service.addSkill(owner, name, source))
    }

    /**
     * Install from the browser: either a zip in the `archive` part, or a folder — files in `files`
     * with their paths relative to the skill directory in the parallel `paths` JSON array. Both are
     * staged outside the owner root, then run the same pipeline as [addFromDirectory].
     */
    @PostMapping("/upload", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun uploadSkill(exchange: ServerWebExchange): Mono<ResponseEntity<SkillAddResultDto>> =
        exchange.multipartData.flatMap { parts ->
            mono {
                val service = skillRefreshService ?: throw skillSystemNotEnabled()
                val name = textOf(parts, "name").trim()
                val owner = resolveOwner(
                    textOf(parts, "shared").equals("true", ignoreCase = true),
                    textOf(parts, "scope")
                )
                val upload = uploadOf(parts)
                    ?: return@mono badRequest("Upload needs an archive part, or files together with a paths array")
                respond(owner, name, service.addUploaded(owner, name, upload))
            }
        }

    /** Enable or disable one skill: the catalog row flips first, the index follows asynchronously. */
    @PatchMapping("/enabled")
    fun setEnabled(
        @RequestBody request: SkillEnabledRequest,
        @RequestParam(required = false) scope: String? = null
    ): Mono<SkillEnabledDto> = mono {
        val service = skillCatalogService ?: throw skillSystemNotEnabled()
        val actor = getCurrentUserId()
        val owner = SkillOwnerContext(resolveWriteOwner(parseAssetScope(scope)))
        requireWritable(request.name, owner, service)
        when (val result = service.setEnabled(request.name, owner, request.enabled)) {
            is SkillToggleResult.Applied -> {
                logger.info(
                    "Skill '{}' of owner '{}' set enabled={} by '{}' (indexSynced={})",
                    result.name, owner.userId, result.enabled, actor, result.indexSynced
                )
                SkillEnabledDto(result.name, result.enabled, result.indexSynced)
            }

            is SkillToggleResult.Rejected -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, result.reason)
        }
    }

    /** Delete one skill: catalog row, package, local directory, registry entry and index document. */
    @DeleteMapping("/{name}")
    fun delete(
        @PathVariable name: String,
        @RequestParam(required = false) scope: String? = null
    ): Mono<ResponseEntity<Unit>> = mono {
        val service = skillCatalogService ?: throw skillSystemNotEnabled()
        val removal = skillRefreshService ?: throw skillSystemNotEnabled()
        val owner = requireWritable(name, SkillOwnerContext(resolveWriteOwner(parseAssetScope(scope))), service).entry.userId
        val deleted = removal.deleteSkill(owner, name)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Skill '$name' disappeared concurrently")
        logger.info("Deleted skill '{}' of owner '{}'", deleted.name, owner)
        ResponseEntity.noContent().build<Unit>()
    }

    // ── Outcome mapping ────────────────────────────────────────────────────────

    private suspend fun respond(
        owner: String,
        name: String,
        result: SkillAddResult
    ): ResponseEntity<SkillAddResultDto> = when (result) {
        is SkillAddResult.Added -> {
            logger.info("Installed skill '{}' for owner '{}' through the API", name, owner)
            val sharedInstall = owner == SkillCatalogEntry.DEFAULT_USER_ID
            val dto = visibleSkills(currentOwners()).firstOrNull { it.name == name && it.shared == sharedInstall }
                ?: result.row.toDto()
            ResponseEntity.ok(SkillAddResultDto(skill = dto, warning = shadowWarning(owner, name)))
        }

        is SkillAddResult.NameConflict ->
            ResponseEntity.status(HttpStatus.CONFLICT).body(SkillAddResultDto(message = result.message))

        is SkillAddResult.Invalid ->
            ResponseEntity.badRequest().body(SkillAddResultDto(message = result.message))
    }

    /** A user-level install that hides a same-named shared skill is legal, but worth saying out loud. */
    private suspend fun shadowWarning(owner: String, name: String): String? {
        if (owner == SkillCatalogEntry.DEFAULT_USER_ID) return null
        if (skillCatalog?.findByName(SkillCatalogEntry.DEFAULT_USER_ID, name) == null) return null
        return "Skill '$name' also exists in the shared layer; your own copy takes precedence"
    }

    // ── Ownership and payload guards ───────────────────────────────────────────

    /**
     * The owner a request writes to. A `shared` install targets the read-only `system` layer and is
     * only permitted for the system identity itself (the auth-disabled single-machine case). Otherwise
     * the write owner comes from the request [scope]: `personal` → the caller, `group` → the group
     * bucket, gated by [resolveWriteOwner] so only the group owner may publish group skills.
     */
    private suspend fun resolveOwner(shared: Boolean, scope: String?): String {
        if (shared) {
            val userId = getCurrentUserId()
            if (userId != SkillCatalogEntry.DEFAULT_USER_ID) {
                throw ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Only the shared system owner can publish shared skills"
                )
            }
            return userId
        }
        return resolveWriteOwner(parseAssetScope(scope))
    }

    /** The row the caller may mutate: their own, or a shared one only when they are the system owner. */
    private suspend fun requireWritable(
        name: String,
        owner: SkillOwnerContext,
        service: SkillCatalogService
    ): SkillCatalogView {
        val view = service.find(name, owner)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Skill '$name' is not installed for user '${owner.userId ?: SkillCatalogEntry.DEFAULT_USER_ID}'"
            )
        if (view.shared && owner.userId != SkillCatalogEntry.DEFAULT_USER_ID) {
            throw ResponseStatusException(HttpStatus.FORBIDDEN, "Shared skills are read-only")
        }
        return view
    }

    /**
     * The server-side directory to install from: absolute and existing, and never inside another
     * owner's skill root — the directory picker can reach those, and they are other tenants'
     * private content. The caller's own root (an in-place claim of a skill they wrote) and the
     * shared layer (already readable by everyone) are allowed.
     */
    private fun sourceDirectory(raw: String, owner: String): Path? {
        val path = runCatching { Path.of(raw) }.getOrNull() ?: return null
        if (!path.isAbsolute) return null
        val canonical = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(canonical)) return null
        val config = skillConfig ?: return canonical
        val skillRoot = Path.of(config.rootDir).toAbsolutePath().normalize()
        if (!canonical.startsWith(skillRoot) || canonical == skillRoot) return canonical
        // Directories one level under the root are owner roots, so the first segment names the owner.
        val ownerSegment = skillRoot.relativize(canonical).getName(0).toString()
        if (ownerSegment == SkillEntry.sanitizeSegment(owner) || ownerSegment == SkillCatalogEntry.DEFAULT_USER_ID) {
            return canonical
        }
        throw ResponseStatusException(HttpStatus.FORBIDDEN, "That directory belongs to another user's skill root")
    }

    /** Reassemble one upload: the zip archive, or files paired with the `paths` JSON array. */
    private suspend fun uploadOf(parts: MultiValueMap<String, Part>): SkillUpload? {
        val limit = packageMaxBytes()
        var buffered = 0L
        // One cap across the whole request: a folder upload arrives as many small parts, and
        // buffering each of them up to the per-package cap would multiply the memory a single
        // request can hold by the file count.
        suspend fun read(part: Part): ByteArray {
            val bytes = readBytes(part, limit)
            buffered += bytes.size
            if (buffered > limit) throw tooLarge(limit)
            return bytes
        }

        (parts["archive"]?.firstOrNull() as? FilePart)?.let { return SkillUpload.FromZip(read(it)) }
        val files = parts["files"]?.filterIsInstance<FilePart>()?.takeIf { it.isNotEmpty() } ?: return null
        val relativePaths = parsePathsJson(textOf(parts, "paths"))
        if (relativePaths.size != files.size) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "paths holds ${relativePaths.size} entries but ${files.size} files were uploaded"
            )
        }
        return SkillUpload.FromFiles(files.mapIndexed { index, part ->
            SkillUploadFile(relativePaths[index], read(part))
        })
    }

    private fun parsePathsJson(json: String): List<String> = try {
        SharedObjectMapper.instance.readValue(json, Array<String>::class.java).toList()
    } catch (e: Exception) {
        throw ResponseStatusException(
            HttpStatus.BAD_REQUEST,
            "paths must be a JSON array of relative file paths, e.g. [\"pdf/SKILL.md\"]: ${e.message}"
        )
    }

    private suspend fun textOf(parts: MultiValueMap<String, Part>, field: String): String =
        parts[field]?.firstOrNull()?.let { String(readBytes(it, packageMaxBytes()), Charsets.UTF_8) } ?: ""

    /** Buffer one part into memory, capped so a single part cannot exhaust the server. */
    private suspend fun readBytes(part: Part, limit: Int): ByteArray {
        val joined: DataBuffer = try {
            DataBufferUtils.join(part.content(), limit).awaitSingleOrNull()
                ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty upload part")
        } catch (e: DataBufferLimitException) {
            throw tooLarge(limit)
        }
        return try {
            ByteArray(joined.readableByteCount()).also { joined.read(it) }
        } finally {
            DataBufferUtils.release(joined)
        }
    }

    private fun packageMaxBytes(): Int =
        (skillConfig ?: SkillConfig()).packageMaxBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    private fun tooLarge(limit: Int) =
        ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "A skill package must stay under $limit bytes")

    // ── View assembly ──────────────────────────────────────────────────────────

    private suspend fun visibleSkills(owners: Collection<String>): List<SkillDto> {
        val candidates = skillAccessResolver?.listScopedSkillsForOwners(owners).orEmpty()
        val rows = skillCatalogService?.listForOwners(owners)
        // No catalog behind the service (single-machine/dev): the on-disk registry snapshot is all there is.
        if (rows.isNullOrEmpty()) return candidates.map { it.toDto() }
        val installed = candidates.associate { it.skill.name to it.skill }
        return rows.map { it.toDto(installed[it.entry.name]) }
    }

    private fun SkillCatalogView.toDto(installed: SkillInfo?): SkillDto = SkillDto(
        name = entry.name,
        description = installed?.description,
        tags = installed?.tags?.toList().orEmpty(),
        version = entry.version,
        enabled = entry.enabled,
        shared = shared,
        installPath = entry.installPath,
        installedOnDisk = installedOnDisk,
        indexed = entry.syncState == SkillSyncState.SYNCED && entry.indexedChecksum == entry.checksum
    )

    private fun ScopedSkill.toDto(): SkillDto = SkillDto(
        name = skill.name,
        description = skill.description,
        tags = skill.tags.toList(),
        version = catalogEntry?.version ?: SkillCatalogEntry.DEFAULT_VERSION,
        enabled = catalogEntry?.enabled ?: true,
        shared = shared,
        installPath = skill.location.parent?.toString() ?: skill.location.toString(),
        installedOnDisk = true,
        indexed = false
    )

    private fun SkillCatalogEntry.toDto(): SkillDto = SkillDto(
        name = name,
        description = null,
        tags = emptyList(),
        version = version,
        enabled = enabled,
        shared = userId == SkillCatalogEntry.DEFAULT_USER_ID,
        installPath = installPath,
        installedOnDisk = true,
        indexed = syncState == SkillSyncState.SYNCED && indexedChecksum == checksum
    )

    private fun badRequest(message: String): ResponseEntity<SkillAddResultDto> =
        ResponseEntity.badRequest().body(SkillAddResultDto(message = message))

    private fun skillSystemNotEnabled(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "The skill system is not enabled (set easyai.skills.enabled=true, with easyai.r2dbc.enabled on)"
    )

}

// ─── DTOs ────────────────────────────────────────────────────────────────────

/**
 * One visible skill. Content fields ([description], [tags]) come from the installed copy and are
 * null/empty while the directory is missing; lifecycle fields come from the catalog row.
 *
 * @param shared the skill comes from the read-only `system` layer
 * @param installedOnDisk false means the next sync restores it from the object-storage package
 * @param indexed false means `skill_search` cannot find it yet (index pending, failed, or disabled)
 */
data class SkillDto(
    val name: String,
    val description: String?,
    val tags: List<String> = emptyList(),
    val version: String = SkillCatalogEntry.DEFAULT_VERSION,
    val enabled: Boolean = true,
    val shared: Boolean = false,
    val installPath: String = "",
    val installedOnDisk: Boolean = true,
    val indexed: Boolean = false
)

/** Install a skill from a server-side directory. [shared] publishes it to every user. */
data class SkillAddRequest(
    val name: String = "",
    val sourcePath: String = "",
    val shared: Boolean = false
)

/**
 * Outcome of an install. Exactly one of [skill] and [message] is set: 200 carries the new entry,
 * 409 the name-collision reason, 400/413 the reason the package was refused.
 */
data class SkillAddResultDto(
    val skill: SkillDto? = null,
    val message: String? = null,
    val warning: String? = null
)

data class SkillEnabledRequest(
    val name: String = "",
    val enabled: Boolean = true
)

data class SkillEnabledDto(
    val name: String,
    val enabled: Boolean,
    val indexSynced: Boolean
)
