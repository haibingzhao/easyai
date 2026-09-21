package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import java.nio.file.Path

/**
 * Derives the granularity of a skill from where it is installed.
 *
 * Pure function over the catalog row — the registry and [SkillInfo] stay untouched, since
 * "which slice does this belong to" is a property of the install path, not of the skill model.
 * Anything that cannot be classified falls back to [SkillScope.GLOBAL], matching the memory
 * subsystem's global-by-default stance: a misfiled skill should stay discoverable, never vanish.
 */
object SkillScopeResolver {

    /**
     * Resolve `(scope, projectPath)` for a catalog row. [Pair.second] is null for
     * [SkillScope.GLOBAL] and for project paths that cannot be recovered from the install path.
     */
    @JvmStatic
    fun resolve(entry: SkillCatalogEntry, config: SkillConfig): Pair<SkillScope, Path?> =
        resolveScopeOf(Path.of(entry.installPath), entry.name, config)

    /**
     * Granularity token of the unique catalog index: `""` for GLOBAL, else the leading hex of
     * SHA-256 over the normalized project path. Deliberately one-way — the path itself is never
     * read back out of the hash; [resolve] stays the only scope derivation from `install_path`.
     */
    @JvmStatic
    fun projectHashOf(projectPath: Path?): String {
        projectPath ?: return SkillCatalogEntry.GLOBAL_HASH
        val normalized = projectPath.toAbsolutePath().normalize().toString()
        return SkillChecksums.sha256Hex(normalized).take(PROJECT_HASH_LENGTH)
    }

    /**
     * The granularity keys one request may see a skill under, nearest first: [projectPath] itself,
     * each ancestor workspace root (a skill installed higher up the tree still applies to a nested
     * session), then null for the GLOBAL fallback. Registry lookup, prompt visibility and the
     * catalog row selection must all iterate **this** sequence, or the prompt could advertise what
     * `load_skill` then refuses.
     */
    @JvmStatic
    fun candidateRoots(projectPath: Path?): List<Path?> {
        if (projectPath == null) return listOf(null)
        val roots = mutableListOf<Path?>()
        var current: Path? = projectPath.toAbsolutePath().normalize()
        while (current != null) {
            roots.add(current)
            current = current.parent
        }
        roots.add(null)
        return roots
    }

    /**
     * Overload for a freshly discovered skill that has no catalog row yet — used by the
     * first-claim backfill, where only the on-disk location is known.
     */
    @JvmStatic
    fun resolve(skill: SkillInfo, config: SkillConfig): Pair<SkillScope, Path?> {
        val installPath = skill.location.parent ?: return SkillScope.GLOBAL to null
        return resolveScopeOf(installPath, skill.name, config)
    }

    private fun resolveScopeOf(rawInstallPath: Path, name: String, config: SkillConfig): Pair<SkillScope, Path?> {
        val installPath = rawInstallPath.toAbsolutePath().normalize()
        val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()

        val underHomeSkillDir = config.homeSkillDirs.any { dir ->
            installPath.startsWith(home.resolve(dir).normalize())
        }
        if (underHomeSkillDir) return SkillScope.GLOBAL to null

        val projectPath = stripSuffix(installPath, name, config.homeSkillDirs)
            ?: return SkillScope.GLOBAL to null
        return SkillScope.PROJECT to projectPath
    }

    /**
     * Recover the workspace root by removing a trailing `{skillDirRoot}/{name}` segment, e.g.
     * `/p/q/.easyai/skills/foo` -> `/p/q`. Returns null when the path matches none of the
     * known skill directory names.
     */
    private fun stripSuffix(installPath: Path, name: String, homeSkillDirs: List<String>): Path? {
        for (dir in homeSkillDirs) {
            val suffix = Path.of(dir).resolve(name)
            if (!installPath.endsWith(suffix)) continue
            var project: Path = installPath
            repeat(suffix.nameCount) {
                project = project.parent ?: return null
            }
            return project.takeIf { it.nameCount > 0 }
        }
        return null
    }

    private const val PROJECT_HASH_LENGTH = 16
}
