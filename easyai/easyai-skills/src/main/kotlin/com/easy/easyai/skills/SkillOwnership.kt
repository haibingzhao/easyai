package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** What the catalog gate of `load_skill` concluded about one request. */
sealed interface SkillLoadPermission {
    /**
     * The requesting user owns an enabled row for this skill.
     *
     * @property installPath the catalog-authoritative skill directory. [SkillTool] uses it to
     *   reject a hit from the process-wide name-keyed registry whose location differs — either
     *   another user owns a same-named skill, or the registry drifted after install/uninstall.
     *   Null when the catalog itself is absent, so pre-RAG callers keep the whitelist-only path
     *   and nothing extra is enforced.
     */
    data class Allowed(val installPath: String? = null) : SkillLoadPermission

    /** No row for this user: the registry may still hold a same-named skill from another tenant. */
    data object NotInstalled : SkillLoadPermission

    /** A row exists but the owner switched it off. */
    data object Disabled : SkillLoadPermission
}

/**
 * The two catalog facts one request needs, derived from a **single** pass over that user's rows.
 *
 * @property userId whose slices and rows this request may read
 * @property enabledNames names this user may use, or null when the catalog could not be read — null
 *   means "do not filter", so an outage hides no skills but also vouches for none
 */
internal data class SkillTenant(val userId: String, val enabledNames: Set<String>?)

/**
 * Decides which catalog rows a requesting user may act on.
 *
 * The registry is process-wide and keyed by (name, granularity), so retrieval and loading must agree
 * on *one* owner per request, otherwise a search that found a skill could not load it — or worse,
 * `load_skill` would hand over another user's same-named skill. [tenantOf] resolves the owner once:
 * a user with their own rows uses them; a user with none falls back to the shared `system` rows
 * that a disk scan claimed. Falling back is what keeps server-level skills usable in a
 * single-instance deployment without ever widening access to a *concrete* other user's rows, because
 * no resolution path can name anybody but the requester or `system`.
 *
 * Enablement is deliberately name-level ([SkillTenant.enabledNames] does not carry a granularity):
 * an agent whitelisted for "pdf-report" may use that name at any granularity it can see.
 */
internal object SkillOwnership {

    private val logger = LoggerFactory.getLogger(SkillOwnership::class.java)

    /**
     * Resolve the owner **and** its enabled skill names for [requested].
     *
     * Both come out of one read of the table, because a `skill_search` exchange needs the owner
     * before it can query the index and the enablement set right after; asking twice would double the
     * catalog traffic of the hottest discovery call.
     */
    @JvmStatic
    suspend fun tenantOf(catalog: AsyncSkillCatalogStore?, requested: String?): SkillTenant {
        val user = requested?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        if (catalog == null) return SkillTenant(user, null)
        if (user == SkillCatalogEntry.DEFAULT_USER_ID) return SkillTenant(user, enabledNamesOf(catalog, user))
        val rows = runCatching { catalog.listByUser(user) }
            .onFailure { logger.warn("Failed to list the skill catalog of '{}': {}", user, it.message) }
            .getOrNull()
            ?: return SkillTenant(user, null)
        if (rows.isEmpty()) {
            return SkillTenant(SkillCatalogEntry.DEFAULT_USER_ID, enabledNamesOf(catalog, SkillCatalogEntry.DEFAULT_USER_ID))
        }
        return SkillTenant(user, rows.filter { it.enabled }.mapTo(mutableSetOf()) { it.name })
    }

    /** Catalog owner whose slices this request should read, given the authenticated [requested]. */
    @JvmStatic
    suspend fun effectiveUserId(catalog: AsyncSkillCatalogStore?, requested: String?): String =
        tenantOf(catalog, requested).userId

    private suspend fun enabledNamesOf(catalog: AsyncSkillCatalogStore, userId: String): Set<String>? =
        runCatching { catalog.listByUser(userId).filter { it.enabled }.mapTo(mutableSetOf()) { it.name } }
            .onFailure { logger.warn("Skill catalog unreadable for '{}', skipping the enablement filter: {}", userId, it.message) }
            .getOrNull()

    /**
     * The third authorization gate, after slice isolation and the search-time catalog filter.
     *
     * @param catalog null when the catalog layer is off, in which case callers keep the previous
     *   whitelist-only behaviour instead of failing
     * @param projectPath the requesting session's workspace; picks which granularity's row the
     *   request resolves to (nearest ancestor PROJECT first, GLOBAL last)
     */
    @JvmStatic
    suspend fun checkLoad(
        catalog: AsyncSkillCatalogStore?,
        name: String,
        requestedUserId: String?,
        projectPath: Path?,
        config: SkillConfig
    ): SkillLoadPermission {
        val store = catalog ?: return SkillLoadPermission.Allowed()
        val userId = tenantOf(store, requestedUserId).userId
        val row = resolveRow(store, name, userId, projectPath, config) ?: return SkillLoadPermission.NotInstalled
        return if (row.enabled) SkillLoadPermission.Allowed(row.installPath) else SkillLoadPermission.Disabled
    }

    /**
     * The single catalog row a request for [name] from [projectPath] resolves to, or null when the
     * owner has no row visible at that granularity.
     *
     * Same-named rows of different projects are distinct catalog entries since V6; which one a
     * request addresses is decided here and only here, walking
     * [SkillScopeResolver.candidateRoots] in order so the registry lookup and this gate cannot
     * disagree about the winner.
     */
    @JvmStatic
    suspend fun resolveRow(
        catalog: AsyncSkillCatalogStore,
        name: String,
        userId: String,
        projectPath: Path?,
        config: SkillConfig
    ): SkillCatalogEntry? {
        val rows = catalog.listByName(name, userId)
        if (rows.isEmpty()) return null
        val byGranularity = HashMap<Path?, SkillCatalogEntry>(rows.size)
        for (row in rows) {
            byGranularity.putIfAbsent(SkillScopeResolver.resolve(row, config).second, row)
        }
        for (root in SkillScopeResolver.candidateRoots(projectPath)) {
            byGranularity[root]?.let { return it }
        }
        return null
    }
}
