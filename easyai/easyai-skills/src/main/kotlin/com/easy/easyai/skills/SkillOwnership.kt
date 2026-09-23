package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import java.nio.file.Path

/** What the catalog gate of `load_skill` concluded about one request. */
sealed interface SkillLoadPermission {
    /**
     * An enabled row authorizes this install directory, which the caller must match to the registry.
     * Null only when the catalog is absent; callers must enforce shared-source access separately.
     */
    data class Allowed(val installPath: String? = null) : SkillLoadPermission

    data object NotInstalled : SkillLoadPermission

    /** A row exists but the owner switched it off; do not fall back to a different source. */
    data object Disabled : SkillLoadPermission
}

/** Catalog access is limited to the requester and system, with requester precedence per identity. */
internal object SkillOwnership {

    /** Exact PROJECT before GLOBAL; for each identity the user's row shadows system, even disabled. */
    @JvmStatic
    suspend fun checkLoad(
        catalog: AsyncSkillCatalogStore?,
        name: String,
        requestedUserId: String?,
        projectPath: Path?,
        config: SkillConfig
    ): SkillLoadPermission {
        val store = catalog ?: return SkillLoadPermission.Allowed()
        val user = requestedUserId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        val ownRows = rowsByIdentity(store.listByName(name, user), user, config)
        var systemRows: Map<SkillKey, SkillCatalogEntry>? = null
        for (root in SkillScopeResolver.candidateRoots(projectPath)) {
            val key = SkillKey(name, root)
            var row = ownRows[key]
            if (row == null && user != SkillCatalogEntry.DEFAULT_USER_ID) {
                val shared = systemRows ?: rowsByIdentity(
                    store.listByName(name, SkillCatalogEntry.DEFAULT_USER_ID), SkillCatalogEntry.DEFAULT_USER_ID, config
                ).also { systemRows = it }
                row = shared[key]
            }
            if (row != null) {
                return if (row.enabled) SkillLoadPermission.Allowed(row.installPath) else SkillLoadPermission.Disabled
            }
        }
        return SkillLoadPermission.NotInstalled
    }

    /** Resolve a row for one explicit owner; management callers must not silently switch owners. */
    @JvmStatic
    suspend fun resolveRow(
        catalog: AsyncSkillCatalogStore,
        name: String,
        userId: String,
        projectPath: Path?,
        config: SkillConfig
    ): SkillCatalogEntry? {
        val rows = rowsByIdentity(catalog.listByName(name, userId), userId, config)
        for (root in SkillScopeResolver.candidateRoots(projectPath)) {
            rows[SkillKey(name, root)]?.let { return it }
        }
        return null
    }

    /** Invalid identities fail closed rather than exposing a shadowed system or GLOBAL row. */
    @JvmStatic
    fun rowsByIdentity(
        rows: List<SkillCatalogEntry>,
        userId: String,
        config: SkillConfig
    ): Map<SkillKey, SkillCatalogEntry> {
        val byIdentity = LinkedHashMap<SkillKey, SkillCatalogEntry>()
        for (row in rows) {
            if (row.userId != userId) continue
            val (_, projectPath) = SkillScopeResolver.resolve(row, config)
            byIdentity.putIfAbsent(SkillKey(row.name, projectPath), row)
        }
        return byIdentity
    }
}
