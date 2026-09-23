package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillScope
import java.nio.file.Path

/** Scope is derived from directory boundaries, never from a skill's declared name. */
object SkillScopeResolver {

    @JvmStatic
    fun resolve(entry: SkillCatalogEntry, config: SkillConfig): Pair<SkillScope, Path?> =
        requireNotNull(classify(entry, config)) { "Unknown skill install root: ${entry.installPath}" }

    @JvmStatic
    fun resolve(skill: SkillInfo, config: SkillConfig): Pair<SkillScope, Path?> =
        requireNotNull(classify(skill, config)) { "Unknown skill install root for ${skill.location}" }

    /** A catalog identity must agree with its source; never authorize it in a newly derived scope. */
    @JvmStatic
    fun classify(entry: SkillCatalogEntry, config: SkillConfig): Pair<SkillScope, Path?>? {
        val installPath = SkillPaths.canonicalizeOrNull(entry.installPath) ?: return null
        val identity = classify(Path.of(installPath), config) ?: return null
        val projectPath = identity.second
        require(entry.projectHash == projectHashOf(projectPath) &&
            entry.indexProjectPath == projectPath?.let { SkillPaths.canonicalize(it) }) {
            "Skill catalog identity differs from its install path: ${entry.id}"
        }
        return identity
    }

    @JvmStatic
    fun classify(skill: SkillInfo, config: SkillConfig): Pair<SkillScope, Path?>? {
        val installPath = Path.of(SkillPaths.canonicalize(skill.location)).parent ?: return null
        return classify(installPath, config)
    }

    /**
     * Recognise the nearest configured skill-directory boundary, including nested skill folders.
     * A config.paths entry inside a project skill tree does not turn that project into a shared
     * source. Only a home skill root or an explicit source outside a project skill tree is GLOBAL.
     */
    @JvmStatic
    fun classify(rawInstallPath: Path, config: SkillConfig): Pair<SkillScope, Path?>? {
        val installPath = Path.of(SkillPaths.canonicalize(rawInstallPath))
        val home = Path.of(SkillPaths.canonicalize(Path.of(System.getProperty("user.home"))))
        val dirNames = config.homeSkillDirs.map { Path.of(it).normalize() }
        val homeRoots = dirNames.map { home.resolve(it).normalize() }.toSet()
        val projectDirNames = dirNames.filter {
            !it.isAbsolute && it.toString().isNotEmpty() && !it.startsWith("..")
        }.sortedByDescending { it.nameCount }
        var boundary: Path? = installPath
        while (boundary != null) {
            if (boundary in homeRoots) return SkillScope.GLOBAL to null
            for (dir in projectDirNames) {
                if (!boundary.endsWith(dir)) continue
                var project: Path? = boundary
                repeat(dir.nameCount) { project = project?.parent }
                if (project != null) return SkillScope.PROJECT to project
            }
            boundary = boundary.parent
        }
        val workDir = Path.of(SkillPaths.canonicalize(Path.of(config.workDir)))
        val explicitlyShared = config.paths.any { path ->
            val root = when {
                path.startsWith("~/") -> home.resolve(path.removePrefix("~/"))
                else -> workDir.resolve(path)
            }
            installPath.startsWith(Path.of(SkillPaths.canonicalize(root)))
        }
        return if (explicitlyShared) SkillScope.GLOBAL to null else null
    }

    /** GLOBAL has an empty token; PROJECT hashes its canonical workspace path. */
    @JvmStatic
    fun projectHashOf(projectPath: Path?): String {
        projectPath ?: return SkillCatalogEntry.GLOBAL_HASH
        return SkillChecksums.sha256Hex(SkillPaths.canonicalize(projectPath)).take(PROJECT_HASH_LENGTH)
    }

    /** Only this exact workspace and GLOBAL are candidates; projects do not inherit each other. */
    @JvmStatic
    fun candidateRoots(projectPath: Path?): List<Path?> =
        if (projectPath == null) listOf(null) else listOf(Path.of(SkillPaths.canonicalize(projectPath)), null)

    private const val PROJECT_HASH_LENGTH = 16
}
