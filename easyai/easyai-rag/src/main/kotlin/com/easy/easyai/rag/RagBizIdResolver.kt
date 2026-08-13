package com.easy.easyai.rag

import java.nio.file.Path
import java.security.MessageDigest

/**
 * Derives EasyRAG `biz_id` values for memory scopes.
 *
 * EasyRAG treats `biz_id` as a hard storage filter dimension (shared tables
 * with a `(id, workspace, biz_id)` composite key); cross-biz_id queries are
 * impossible. Memory therefore maps:
 *
 * - GLOBAL scope  -> `u_{userId}` (user-level slice, visible across projects)
 * - PROJECT scope -> `u_{userId}-{projectKey}` (user + project slice)
 *
 * Charset constraint (shared with EasyRAG workspace validation):
 * `^[a-zA-Z0-9_\-][a-zA-Z0-9_\-\.]{0,63}$`.
 */
internal object RagBizIdResolver {

    private const val USER_PREFIX = "u_"
    private const val MAX_GLOBAL_LENGTH = 26
    private const val SEGMENT_MAX_LENGTH = 20
    private const val HASH_LENGTH = 8

    /** biz_id for GLOBAL scope: `u_{sanitized userId}`, capped at [MAX_GLOBAL_LENGTH]. */
    fun globalBizId(userId: String?): String {
        val user = sanitize(userId)?.take(MAX_GLOBAL_LENGTH - USER_PREFIX.length) ?: "system"
        return USER_PREFIX + user
    }

    /**
     * biz_id for PROJECT scope: `u_{userId}-{lastPathSegment}-{pathHash8}`.
     * Returns null when [projectPath] is absent (PROJECT operations degrade).
     */
    fun projectBizId(userId: String?, projectPath: Path?): String? {
        if (projectPath == null) return null
        val user = (sanitize(userId) ?: "system").take(SEGMENT_MAX_LENGTH)
        val segment = (sanitize(projectPath.fileName?.toString()) ?: "p").take(SEGMENT_MAX_LENGTH)
        val hash = shortHash(projectPath.toAbsolutePath().normalize().toString())
        return "$USER_PREFIX$user-$segment-$hash"
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

    /** First [HASH_LENGTH] hex chars of the SHA-256 of [value]. */
    private fun shortHash(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }.take(HASH_LENGTH)
    }
}
