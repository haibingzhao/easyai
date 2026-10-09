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
    suspend fun listScopedSkills(userId: String?): List<ScopedSkill> =
        listScopedSkillsForOwners(listOf(ownerOf(userId)))

    /**
     * Group-aware form: the effective skill set visible to an ordered owner set (self → group →
     * system). A name is authorized by the highest-priority owner's catalog row, and only that
     * row's install path is exposed — so a member sees their group's skills shadowed by their own,
     * exactly as [listScopedSkills] shadows the shared layer for a single owner.
     */
    suspend fun listScopedSkillsForOwners(owners: Collection<String>): List<ScopedSkill> {
        val ordered = normalizeOwners(owners)
        val store = catalog
            ?: return registry.visibleForOwners(ordered).map { ScopedSkill(it, null) }
        // A store that misreports ownership can never authorize: a row is kept only under the owner
        // it reports, and the first owner in priority order claims each name. One batched read for
        // the whole visibility set — this runs per turn, so a query per owner is a real cost.
        val rows = store.listByOwners(ordered)
        val rowByName = LinkedHashMap<String, SkillCatalogEntry>()
        for (owner in ordered) {
            rows.filter { it.userId == owner }
                .forEach { rowByName.putIfAbsent(it.name, it) }
        }
        val result = mutableListOf<ScopedSkill>()
        for (skill in registry.visibleForOwners(ordered)) {
            // Rows, not the in-memory snapshot, decide the winner: a delete, a disable and a
            // restore all act on a row, and the snapshot can lag it by one sync pass.
            val row = rowByName[skill.name] ?: continue
            val dir = skill.location.parent ?: continue
            if (SkillPaths.canonicalizeOrNull(row.installPath) != SkillPaths.canonicalize(dir)) continue
            result.add(ScopedSkill(skill, row))
        }
        return result.sortedBy { it.skill.name }
    }

    private fun ownerOf(userId: String?): String =
        userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID

    /** Distinct non-blank owners in priority order, with `system` appended as the final fallback. */
    private fun normalizeOwners(owners: Collection<String>): List<String> {
        val system = SkillCatalogEntry.DEFAULT_USER_ID
        val cleaned = owners.filter { it.isNotBlank() }.distinct()
        val base = if (cleaned.isEmpty()) listOf(system) else cleaned
        return if (system in base) base else base + system
    }
}
