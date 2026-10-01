package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry

/** A registry candidate and the exact catalog row authorizing its install location, if configured. */
data class ScopedSkill(val skill: SkillInfo, val catalogEntry: SkillCatalogEntry?) {
    /** True when the authorizing row belongs to the shared `system` layer. */
    val shared: Boolean
        get() = catalogEntry?.userId == SkillCatalogEntry.DEFAULT_USER_ID
}

/**
 * Owner-granular resolution shared by command, prompt, load and management entry points.
 *
 * The name binding happens once, here: catalog rows decide who owns a name (the viewer's own row
 * shadows the shared one), and only the install path that winning row pins is exposed.
 */
class SkillAccessResolver(
    private val registry: SkillRegistry,
    private val catalog: AsyncSkillCatalogStore?
) {

    /**
     * The effective skill set visible to [userId] — their own plus shared `system` skills — with
     * no enabled or agent-whitelist filtering. Catalog failures propagate.
     *
     * Without a catalog the registry snapshot is exposed unbound (single-machine/dev mode);
     * with one, a candidate whose row is missing or whose install path drifted stays hidden —
     * a stale user row cannot grant access through a different system install.
     */
    suspend fun listScopedSkills(userId: String?): List<ScopedSkill> {
        val owner = userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
        val store = catalog
            ?: return registry.visibleFor(owner).map { ScopedSkill(it, null) }
        // A store that misreports ownership can never authorize: rows are kept only under their own userId.
        val ownRows = store.listByUser(owner).filter { it.userId == owner }.associateBy { it.name }
        val systemRows = if (owner == SkillCatalogEntry.DEFAULT_USER_ID) emptyMap()
        else store.listByUser(SkillCatalogEntry.DEFAULT_USER_ID).filter { it.userId == SkillCatalogEntry.DEFAULT_USER_ID }.associateBy { it.name }
        val result = mutableListOf<ScopedSkill>()
        for (skill in registry.visibleFor(owner)) {
            // Rows, not the in-memory snapshot, decide the winner: a delete, a disable and a
            // restore all act on a row, and the snapshot can lag it by one sync pass.
            val row = ownRows[skill.name] ?: systemRows[skill.name] ?: continue
            val dir = skill.location.parent ?: continue
            if (SkillPaths.canonicalizeOrNull(row.installPath) != SkillPaths.canonicalize(dir)) continue
            result.add(ScopedSkill(skill, row))
        }
        return result.sortedBy { it.skill.name }
    }
}
