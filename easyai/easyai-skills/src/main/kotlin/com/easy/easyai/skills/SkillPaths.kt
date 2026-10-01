package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillEntry
import java.nio.file.Path

/**
 * Single source of truth for skill directory paths.
 *
 * Canonicalisation rule: **lexical normalisation only** — `toAbsolutePath().normalize()`, never
 * symlink-resolved. This matches catalog writes and the prompt filter and is deterministic across
 * restarts and machines without needing the file to exist. Callers that need symlink resolution do
 * it locally.
 *
 * Layout rule: owner roots are `{rootDir}/{sanitizedOwner}`, skill directories are
 * `{ownerRoot}/{sanitizedName}`. Owner and name segments are sanitized with
 * [SkillEntry.sanitizeSegment] so a hostile or path-bearing user id can never escape the tree.
 */
internal object SkillPaths {

    const val SKILL_FILE_NAME = "SKILL.md"

    /**
     * Canonical string form of [path]: absolute + lexically normalized (`..` and `.` collapsed),
     * **not** symlink-resolved. Safe to persist into `skill.install_path` / `skill.root_path`.
     */
    @JvmStatic
    fun canonicalize(path: Path): String = path.toAbsolutePath().normalize().toString()

    /**
     * Same as [canonicalize] but tolerates a raw string coming out of the DB. Returns null when
     * the value is not a parseable path — that only happens on data corruption, and callers
     * filter it out rather than treating it as a live skill.
     */
    @JvmStatic
    fun canonicalizeOrNull(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return runCatching { canonicalize(Path.of(raw)) }.getOrNull()
    }

    /** Filesystem-safe directory segment for an owner id or skill name. */
    @JvmStatic
    fun safeSegment(value: String): String = SkillEntry.sanitizeSegment(value)

    /** `{rootDir}/{sanitizedOwner}` — the one directory an owner's skills live under. */
    @JvmStatic
    fun ownerRoot(config: SkillConfig, owner: String): Path =
        Path.of(config.rootDir).toAbsolutePath().normalize().resolve(safeSegment(owner))

    /** `{ownerRoot}/{sanitizedName}` — the install directory of one skill of [owner]. */
    @JvmStatic
    fun installDir(ownerRoot: Path, name: String): Path =
        ownerRoot.resolve(safeSegment(name))

    /** True when [path] is strictly inside [root] (lexically, after canonicalisation); [root] itself is not within it. */
    @JvmStatic
    fun isWithin(root: Path, path: Path): Boolean {
        val absoluteRoot = root.toAbsolutePath().normalize()
        val absolutePath = path.toAbsolutePath().normalize()
        return absolutePath != absoluteRoot && absolutePath.startsWith(absoluteRoot)
    }
}
