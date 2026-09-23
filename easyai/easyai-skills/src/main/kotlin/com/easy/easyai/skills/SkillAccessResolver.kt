package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import java.nio.file.Path

/** A registry candidate and the exact catalog row authorizing its install location, if configured. */
data class ScopedSkill(val skill: SkillInfo, val catalogEntry: SkillCatalogEntry?)

/** Shared user/project candidate resolution for command and management entry points. */
class SkillAccessResolver(
    private val registry: SkillRegistry,
    private val catalog: AsyncSkillCatalogStore?,
    private val config: SkillConfig
) {

    /**
     * Returns exact-project and GLOBAL candidates without enabled or agent-whitelist filtering.
     * Same-named PROJECT and GLOBAL entries remain distinct. The user's row shadows system for
     * each (name, project), before install-path matching, so a stale or disabled user row cannot
     * grant access through a different system install. Catalog failures propagate.
     *
     * Without a catalog only explicitly shared GLOBAL sources are exposed, never project trees
     * whose ownership cannot be established. The registry's same-identity root winner is unchanged.
     */
    suspend fun listScopedSkills(userId: String?, projectPath: Path?): List<ScopedSkill> {
        val roots = SkillScopeResolver.candidateRoots(projectPath)
        val candidates = registry.all().mapNotNull { skill ->
            val (scope, project) = SkillScopeResolver.classify(skill, config) ?: return@mapNotNull null
            if (project !in roots || (catalog == null && scope != SkillScope.GLOBAL)) return@mapNotNull null
            SkillKey(skill.name, project) to skill
        }.sortedWith(compareBy({ roots.indexOf(it.first.projectPath) }, { it.first.name }))
        val store = catalog ?: return candidates.map { ScopedSkill(it.second, null) }
        val user = userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        val ownRows = SkillOwnership.rowsByIdentity(store.listByUser(user), user, config)
        val systemRows = if (user == SkillCatalogEntry.DEFAULT_USER_ID) emptyMap() else {
            SkillOwnership.rowsByIdentity(
                store.listByUser(SkillCatalogEntry.DEFAULT_USER_ID), SkillCatalogEntry.DEFAULT_USER_ID, config
            )
        }
        return candidates.mapNotNull { (key, skill) ->
            val row = ownRows[key] ?: systemRows[key] ?: return@mapNotNull null
            val installPath = skill.location.parent ?: return@mapNotNull null
            if (SkillPaths.canonicalizeOrNull(row.installPath) != SkillPaths.canonicalize(installPath)) {
                return@mapNotNull null
            }
            ScopedSkill(skill, row)
        }
    }
}
