package com.easy.easyai.core.knowledge

/**
 * Resolves which owner id a knowledge operation is scoped to.
 *
 * Knowledge lives in one RAG slice per owner id (biz_id `u_{owner}_k`). By default that owner is the
 * calling user, so knowledge is personal. When `easyai.knowledge.shared-within-group` is on and the
 * login carries a group bucket, the owner becomes the group id instead — the household owner and every
 * member then read and write the very same slice, which is how knowledge is shared without any new
 * table or RAG configuration. Memory is deliberately NOT shared this way (it stays per-user).
 *
 * The group bucket is either known explicitly (a request reads it from the token claims) or derived
 * from a runtime owner set `{self, group, system}` by dropping self and system.
 */
object KnowledgeOwnership {

    /** Shared fallback owner, matching the `user_id` column default. */
    const val SYSTEM_OWNER = "system"

    /**
     * The owner id for a knowledge operation: [groupUserId] when sharing is enabled and one is present,
     * otherwise [userId] (degrading to [SYSTEM_OWNER] when blank).
     */
    fun ownerId(userId: String?, groupUserId: String?, sharedWithinGroup: Boolean): String {
        val base = userId?.takeIf { it.isNotBlank() } ?: SYSTEM_OWNER
        return if (sharedWithinGroup && !groupUserId.isNullOrBlank()) groupUserId else base
    }

    /**
     * The group bucket within a runtime owner set, or null when the set carries no group. The set is
     * `{self, group, system}`; dropping [userId] and [SYSTEM_OWNER] leaves the group id.
     */
    fun groupBucket(userId: String?, owners: Collection<String>?): String? {
        val base = userId?.takeIf { it.isNotBlank() } ?: SYSTEM_OWNER
        return owners?.firstOrNull { it.isNotBlank() && it != base && it != SYSTEM_OWNER }
    }
}
