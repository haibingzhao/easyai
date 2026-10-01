package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillSyncState

/** The name-bound model view shared by prompt, load and search; slash commands use the base resolver. */
internal class SkillModelView(
    registry: SkillRegistry,
    catalog: AsyncSkillCatalogStore?
) {
    private val access = SkillAccessResolver(registry, catalog)

    /** Name-bound, enabled-filtered view without a whitelist — the default agent's authorization list. */
    suspend fun listEffective(userId: String?): List<ScopedSkill> =
        access.listScopedSkills(userId)
            // Binding precedes enablement: disabling a personal skill must not resurrect its
            // shared namesake, which the resolver already shadowed out by name.
            .filter { it.catalogEntry?.enabled != false }

    suspend fun list(userId: String?, allowedSkillNames: List<String>): List<ScopedSkill> {
        if (allowedSkillNames.isEmpty()) return emptyList()
        val allowed = allowedSkillNames.toSet()
        return listEffective(userId).filter { it.skill.name in allowed }
    }

    fun indexReady(skill: ScopedSkill): Boolean {
        val row = skill.catalogEntry ?: return false
        return row.enabled && row.syncState == SkillSyncState.SYNCED &&
            row.checksum.isNotBlank() && row.indexedChecksum == row.checksum
    }
}
