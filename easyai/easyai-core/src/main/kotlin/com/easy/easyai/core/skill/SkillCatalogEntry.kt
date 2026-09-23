package com.easy.easyai.core.skill

/**
 * A row of the `skill` catalog table — the directory & lifecycle source of truth for skills.
 *
 * Answers "who owns which skill, where did it come from, is it enabled, what is its content
 * fingerprint". Content itself stays on disk; this record drives per-user RAG slice addressing
 * (startup enumerates owners via [AsyncSkillCatalogStore.listDistinctUserIds] instead of guessing
 * the current request user) and checksum-drift-driven re-indexing.
 *
 * @param id primary key
 * @param name skill name, unique per [userId] within one granularity
 * @param source provenance class: `LOCAL` (claimed from a disk scan, including a SKILL.md an agent
 *   wrote during a chat). Management-facing only — it must not introduce any execution branch.
 * @param version frontmatter `version` when present, else `0.0.0`
 * @param checksum SHA-256 hex of the raw **SKILL.md bytes** (attachments excluded); the
 *   arbiter for "is the index stale / is the local cache outdated"
 * @param enabled lifecycle switch; disabled skills are removed from the index
 * @param installPath absolute path of the skill directory
 * @param origin concrete source address, e.g. the external URL or repository a skill was authored
 *   from; free-form provenance for the management surface
 * @param userId owner; `system` for skills claimed from the filesystem
 * @param projectHash granularity key for the unique index: `""` for GLOBAL, else the leading
 *   SHA-256 hex of the normalized project path. An identity token only — scope is always
 *   derived back from [installPath], never read out of this column.
 * @param createdAt epoch millis
 * @param updatedAt epoch millis
 */
data class SkillCatalogEntry(
    val id: String = "",
    val name: String,
    val source: String = SOURCE_LOCAL,
    val version: String = DEFAULT_VERSION,
    val checksum: String,
    val enabled: Boolean = true,
    val installPath: String,
    val origin: String? = null,
    val userId: String = DEFAULT_USER_ID,
    val projectHash: String = GLOBAL_HASH,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val indexedChecksum: String? = null,
    val syncState: SkillSyncState = if (enabled) SkillSyncState.PENDING_INDEX else SkillSyncState.PENDING_DELETE,
    val revision: Long = 0L,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null,
    /** Persisted slice address, not an authorisation source. */
    val indexProjectPath: String? = null
) {
    companion object {
        const val SOURCE_LOCAL = "LOCAL"

        /** Default owner for filesystem-discovered skills (no user info on disk). */
        const val DEFAULT_USER_ID = "system"

        /** Fallback version when a SKILL.md declares none. */
        const val DEFAULT_VERSION = "0.0.0"

        /** [projectHash] value of a GLOBAL skill. */
        const val GLOBAL_HASH = ""
    }
}

enum class SkillSyncState { PENDING_INDEX, SUBMITTED, SYNCED, PENDING_DELETE, ABSENT }

/** Only projection fields may be completed by an index worker. */
data class SkillSyncUpdate(
    val state: SkillSyncState,
    val indexedChecksum: String?,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null
)
