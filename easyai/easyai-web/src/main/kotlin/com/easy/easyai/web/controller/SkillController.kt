package com.easy.easyai.web.controller

import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.SkillCatalogService
import com.easy.easyai.skills.SkillCatalogView
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillScopeResolver
import com.easy.easyai.skills.SkillToggleResult
import com.easy.easyai.web.security.getCurrentUserId
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import java.nio.file.Path
import reactor.core.publisher.Mono

/**
 * REST controller for Skill queries and the skill catalog.
 *
 * Endpoints:
 * - GET   /api/skills         - List skills visible to the current user, optionally at one project's
 *                               granularity (`projectId`, resolved server-side — never by raw path)
 * - GET   /api/skills/catalog - This user's catalog rows (source/enabled/path)
 * - PATCH /api/skills/enabled - Enable or disable one skill (row + index together)
 *
 * Creating a skill is not an HTTP concern: an agent writes the files with `write` and publishes them
 * with the `refresh_skills` tool, so the two paths cannot disagree about what a valid SKILL.md is.
 *
 * There is deliberately no install/download surface: a skill exists on disk and is indexed *into*
 * RAG for discovery, so serving one back out of the index and unpacking it onto the same disk would
 * be a round trip with no consumer at the other end.
 *
 * Every handler takes the owner from [getCurrentUserId] and never from a request field: a body-supplied
 * userId would let one user toggle or list under another's name.
 *
 * Catalog endpoints answer 503 when their service bean is absent, which is the normal state while
 * `easyai.skills.rag.enabled=false`.
 */
@RestController
@RequestMapping("/api/skills")
class SkillController(
    @param:Autowired(required = false)
    private val skillCatalogService: SkillCatalogService? = null,
    @param:Autowired(required = false)
    private val projectStore: AsyncProjectStore? = null,
    @param:Autowired(required = false)
    private val skillConfig: SkillConfig? = null,
    @param:Autowired(required = false)
    private val skillAccessResolver: SkillAccessResolver? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Configuration candidates include disabled skills and preserve distinct permitted sources. */
    @GetMapping
    fun listSkills(@RequestParam(required = false) projectId: String?): Mono<List<SkillDto>> = mono {
        val userId = getCurrentUserId()
        val projectPath = resolveProjectPath(projectId, userId)
        skillAccessResolver?.listScopedSkills(userId, projectPath).orEmpty().map {
            it.skill.toDto().copy(enabled = it.catalogEntry?.enabled ?: true)
        }
    }

    /** Catalog rows of the caller: provenance, version, enablement, install path. */
    @GetMapping("/catalog")
    fun listCatalog(): Mono<List<CatalogSkillDto>> = mono {
        val service = skillCatalogService ?: throw skillRagNotEnabled()
        val userId = getCurrentUserId()
        service.list(SkillOwnerContext(userId, null)).map { it.toCatalogDto() }
    }

    /**
     * Enable or disable one of the caller's skills, updating the row and the index in one call.
     *
     * The result says whether the index caught up: a row that flipped without the index following is
     * still authoritative (search post-filters on the catalog), but the UI should surface it.
     */
    @PatchMapping("/enabled")
    fun setEnabled(@RequestBody request: SkillEnabledRequest): Mono<SkillEnabledDto> = mono {
        val service = skillCatalogService ?: throw skillRagNotEnabled()
        val userId = getCurrentUserId()
        val owner = ownerOf(request.scope, userId, request.projectId)
        when (val result = service.setEnabled(request.name, owner.context, request.enabled)) {
            is SkillToggleResult.Applied -> {
                logger.info("Skill '{}' of user '{}' set enabled={} (indexSynced={})", result.name, userId, result.enabled, result.indexSynced)
                SkillEnabledDto(name = result.name, enabled = result.enabled, indexSynced = result.indexSynced)
            }

            is SkillToggleResult.Rejected -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, result.reason)
        }
    }

    /**
     * Resolve the owner of a request: caller identity from the security context, project path through
     * the user-scoped project store.
     *
     * A project is addressed by id, never by path, because a client-supplied path would let a request
     * act on a slice of another workspace than the one the caller owns.
     */
    private suspend fun ownerOf(scope: String?, userId: String, projectId: String?): ScopedOwner {
        val resolvedScope = when (scope?.trim()?.lowercase()) {
            null, "", "global" -> SkillScope.GLOBAL
            "project" -> SkillScope.PROJECT
            else -> throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown scope '$scope'; use 'global' or 'project'")
        }
        if (resolvedScope == SkillScope.PROJECT) {
            val path = resolveProjectPath(projectId, userId)
                ?: throw ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "scope=project requires a projectId of a project of this user whose path exists on this server"
                )
            return ScopedOwner(resolvedScope, SkillOwnerContext(userId, path))
        }
        return ScopedOwner(resolvedScope, SkillOwnerContext(userId, null))
    }

    private suspend fun resolveProjectPath(projectId: String?, userId: String): Path? {
        if (projectId.isNullOrBlank()) return null
        val project = projectStore?.findById(projectId, userId)
            ?: throw ResponseStatusException(HttpStatus.NOT_FOUND, "Project not found")
        val path = project.path.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Project directory is unavailable")
        val absolute = Path.of(path).toAbsolutePath().normalize()
        if (!Files.isDirectory(absolute)) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Project directory is unavailable")
        return absolute
    }

    private fun skillRagNotEnabled(): ResponseStatusException = ResponseStatusException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Skill RAG is not enabled (set easyai.skills.rag.enabled=true, with easyai.rag.enabled and easyai.r2dbc.enabled on)"
    )

    private data class ScopedOwner(val scope: SkillScope, val context: SkillOwnerContext)

    private fun SkillInfo.toDto(): SkillDto {
        val (scope, projectPath) = SkillScopeResolver.resolve(this, skillConfig ?: SkillConfig())
        return SkillDto(
            name = name,
            description = description,
            tags = tags.toList(),
            scope = scope.name.lowercase(),
            projectPath = projectPath?.toString()
        )
    }

    private fun SkillCatalogView.toCatalogDto(): CatalogSkillDto = CatalogSkillDto(
        name = entry.name,
        source = entry.source,
        version = entry.version,
        enabled = entry.enabled,
        installPath = entry.installPath,
        origin = entry.origin,
        scope = scope.name.lowercase(),
        projectPath = projectPath?.toString(),
        installedOnDisk = installedOnDisk
    )
}

/**
 * One registry entry as the UI needs it. [scope]/[projectPath] disambiguate same-named skills that
 * coexist at different granularities; both are derived from the install location, never stored.
 */
data class SkillDto(
    val name: String,
    val description: String?,
    val tags: List<String> = emptyList(),
    val scope: String = "global",
    val projectPath: String? = null,
    val enabled: Boolean = true
)

/** One catalog row as the management surface needs to see it. */
data class CatalogSkillDto(
    val name: String,
    val source: String,
    val version: String,
    val enabled: Boolean,
    val installPath: String,
    val origin: String? = null,
    val scope: String,
    val projectPath: String? = null,
    val installedOnDisk: Boolean
)

data class SkillEnabledRequest(
    val name: String = "",
    val enabled: Boolean = true,
    val scope: String? = null,
    val projectId: String? = null
)

data class SkillEnabledDto(
    val name: String,
    val enabled: Boolean,
    val indexSynced: Boolean
)
