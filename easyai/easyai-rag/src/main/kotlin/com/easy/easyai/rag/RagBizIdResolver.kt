package com.easy.easyai.rag

import java.nio.file.Path
import java.security.MessageDigest

/**
 * Derives EasyRAG `biz_id` values for memory, knowledge and skill scopes.
 *
 * EasyRAG treats `biz_id` as a hard storage filter dimension (shared tables
 * with a `(id, workspace, biz_id)` composite key); cross-biz_id queries are
 * impossible. The biz_id encodes both the **user/project scope** and the
 * **content type** (memory vs knowledge vs skill), achieving storage-level isolation:
 *
 * - GLOBAL memory   -> `u_{userId}_m`
 * - GLOBAL knowledge -> `u_{userId}_k`
 * - PROJECT memory   -> `u_{userId}-{projectKey}-{hash8}_m`
 * - PROJECT knowledge -> `u_{userId}-{projectKey}-{hash8}_k`
 * - one skill owner  -> `u_{userId}_s`
 *
 * Skills are owner-granular only: every skill of one user — including the `system` shared
 * layer — lives in that owner's single `_s` slice, so no skill is ever project-addressed.
 *
 * This means vector search, keyword search, and all storage operations
 * are filtered at the storage layer — no metadata post-filter needed.
 *
 * Charset constraint (shared with EasyRAG workspace validation):
 * `^[a-zA-Z0-9_\-][a-zA-Z0-9_\-\.]{0,63}$`.
 */
internal object RagBizIdResolver {

    private const val USER_PREFIX = "u_"
    private const val MAX_GLOBAL_LENGTH = 26
    private const val SEGMENT_MAX_LENGTH = 20
    private const val HASH_LENGTH = 8

    /** Content type suffix for memory. */
    const val MEMORY_TYPE = "m"

    /** Content type suffix for knowledge. */
    const val KNOWLEDGE_TYPE = "k"

    /** Content type suffix for skills. */
    const val SKILL_TYPE = "s"

    /**
     * biz_id for GLOBAL scope: `u_{sanitized userId}_{contentType}`,
     * capped at [MAX_GLOBAL_LENGTH] (before the content type suffix).
     */
    fun globalBizId(userId: String?, contentType: String): String {
        val user = sanitize(userId)?.take(MAX_GLOBAL_LENGTH - USER_PREFIX.length) ?: "system"
        return "$USER_PREFIX${user}_$contentType"
    }

    /**
     * biz_id for PROJECT scope: `u_{userId}-{lastPathSegment}-{pathHash8}_{contentType}`.
     * Returns null when [projectPath] is absent (PROJECT operations degrade).
     */
    fun projectBizId(userId: String?, projectPath: Path?, contentType: String): String? {
        if (projectPath == null) return null
        val user = (sanitize(userId) ?: "system").take(SEGMENT_MAX_LENGTH)
        val segment = (sanitize(projectPath.fileName?.toString()) ?: "p").take(SEGMENT_MAX_LENGTH)
        val hash = shortHash(projectPath.toAbsolutePath().normalize().toString())
        return "$USER_PREFIX$user-$segment-${hash}_$contentType"
    }

    /** Replace characters outside the EasyRAG scope charset with `_`; null when blank. */
    internal fun sanitize(value: String?): String? {
        if (value.isNullOrBlank()) return null
        return value.map { c ->
            when {
                c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '_' || c == '-' || c == '.' -> c
                else -> '_'
            }
        }.joinToString("")
    }

    /**
     * biz_id of the slice holding every skill of one owner, `system` (the shared read-only layer)
     * included. A blank owner degrades to the `system` slice.
     */
    fun skillBizId(userId: String?): String = globalBizId(userId, SKILL_TYPE)

    /**
     * biz_ids of every owner's skill slice, in the order the owners are given — de-duplicated, so
     * an early owner keeps priority for a name a later owner also indexes (shadowing).
     */
    fun skillBizIds(ownerUserIds: List<String>): List<String> =
        ownerUserIds.map { skillBizId(it) }.distinct()

    /** First [HASH_LENGTH] hex chars of the SHA-256 of [value]. */
    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }
}
