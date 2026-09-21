package com.easy.easyai.skills

import java.nio.file.Path

/**
 * Single source of truth for turning a skill directory into the string that lands in
 * `skill.install_path` — and back again when comparing.
 *
 * Three call sites used to normalise paths independently ([SkillCatalogSyncService],
 * [SkillPromptSource], [SkillTool]) with subtly different semantics: two of them used
 * `toAbsolutePath().normalize()` (does **not** resolve symlinks), one used `toRealPath()` (does).
 * Whenever a workspace path involved a symlinked directory, the catalog wrote the unresolved form
 * while the tool compared against the resolved one, and legitimate skills were rejected as
 * "wrong install path".
 *
 * The unified rule: **lexical normalisation only** — `toAbsolutePath().normalize()`. This matches
 * the majority behaviour (catalog writes, prompt filter) and is deterministic across restarts and
 * machines without needing the file to exist. Callers that need symlink resolution for their own
 * purposes (e.g. dedup during discovery) do it locally.
 */
internal object SkillPaths {

    /**
     * Canonical string form of [path]: absolute + lexically normalized (`..` and `.` collapsed),
     * **not** symlink-resolved. Safe to persist into `skill.install_path` and to compare against
     * another result of this function.
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
}
