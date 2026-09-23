package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillSyncState
import java.nio.file.Path

/** The name-bound model view shared by prompt, load and search; slash commands use the base resolver. */
internal class SkillModelView(
    registry: SkillRegistry,
    catalog: AsyncSkillCatalogStore?,
    private val config: SkillConfig
) {
    private val access = SkillAccessResolver(registry, catalog, config)

    /** Name-bound, enabled-filtered view without a whitelist — the default agent's authorization list. */
    suspend fun listEffective(userId: String?, projectPath: Path?): List<ScopedSkill> =
        access.listScopedSkills(userId, projectPath)
            .sortedBy { if (scopeOf(it) == SkillScope.PROJECT) 0 else 1 }
            .distinctBy { it.skill.name }
            .filter { it.catalogEntry?.enabled != false }

    suspend fun list(
        userId: String?,
        projectPath: Path?,
        allowedSkillNames: List<String>
    ): List<ScopedSkill> {
        if (allowedSkillNames.isEmpty()) return emptyList()
        val allowed = allowedSkillNames.toSet()
        // Choose the name binding BEFORE enablement: disabling a project override must not
        // resurrect its GLOBAL namesake. The base resolver has already resolved owner fallback.
        return listEffective(userId, projectPath).filter { it.skill.name in allowed }
    }

    fun scopeOf(skill: ScopedSkill): SkillScope = SkillScopeResolver.resolve(skill.skill, config).first

    fun indexReady(skill: ScopedSkill): Boolean {
        val row = skill.catalogEntry ?: return false
        return row.enabled && row.syncState == SkillSyncState.SYNCED &&
            row.checksum.isNotBlank() && row.indexedChecksum == row.checksum
    }
}
