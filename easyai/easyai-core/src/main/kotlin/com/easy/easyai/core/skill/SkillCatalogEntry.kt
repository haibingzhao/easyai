package com.easy.easyai.core.skill

/**
 * A row of the `skill` catalog table — the directory & lifecycle source of truth for skills.
 *
 * Answers "who owns which skill, where is it installed, where is its package, is it enabled,
 * what is its content fingerprint". Skill **content** is authoritative in the OSS package at
 * [objectKey]; the local directory under the owner's root is a working cache.
 *
 * Ownership is two-level: a regular user owns `~/.easyai/skills/{userId}/{name}/`, and the
 * `system` owner ([DEFAULT_USER_ID]) owns the shared read-only layer every user can see.
 *
 * @param id primary key
 * @param name skill name, unique within its [userId] owner
 * @param source provenance class: `LOCAL` (claimed from the user's skill root, including a
 *   SKILL.md an agent wrote during a chat). Management-facing only — it must not introduce any
 *   execution branch.
 * @param version frontmatter `version` when present, else `0.0.0`
 * @param checksum SHA-256 hex of the whole skill directory (every regular file's relative path
 *   and bytes, deterministically ordered); the arbiter for "is the index stale / is the local
 *   cache outdated". The zip at [objectKey] is a verbatim copy of the directory, so restoring it
 *   re-derives this digest identically.
 * @param enabled lifecycle switch; disabled skills are removed from the index
 * @param rootPath absolute path of the owner's skill root (e.g. `~/.easyai/skills/alice`)
 * @param installPath absolute path of the skill directory; always [rootPath]`/{name}`
 * @param objectKey key of the zip package in object storage, e.g. `skills/alice/code-review.zip`
 * @param userId owner; `system` for the shared read-only layer
 * @param createdAt epoch millis
 * @param updatedAt epoch millis
 * @param indexedChecksum checksum currently present in the discovery index
 * @param syncState index lifecycle state
 * @param revision optimistic concurrency token
 * @param nextAttemptAt retry hint for a failed sync
 * @param lastError last sync failure message
 */
data class SkillCatalogEntry(
    val id: String = "",
    val name: String,
    val source: String = SOURCE_LOCAL,
    val version: String = DEFAULT_VERSION,
    val checksum: String,
    val enabled: Boolean = true,
    val rootPath: String,
    val installPath: String,
    val objectKey: String = "",
    val userId: String = DEFAULT_USER_ID,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val indexedChecksum: String? = null,
    val syncState: SkillSyncState = if (enabled) SkillSyncState.PENDING_INDEX else SkillSyncState.PENDING_DELETE,
    val revision: Long = 0L,
    val nextAttemptAt: Long? = null,
    val lastError: String? = null
) {
    companion object {
        const val SOURCE_LOCAL = "LOCAL"

        /** Owner of the shared read-only skill layer, visible to every user. */
        const val DEFAULT_USER_ID = "system"

        /** Fallback version when a SKILL.md declares none. */
        const val DEFAULT_VERSION = "0.0.0"
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
