package com.easy.easyai.core.permission

import com.easy.easyai.core.model.ProjectKind

/**
 * Rule bucket a project-less chat reads and writes.
 *
 * Auto-approve rules are stored per project. A temporary workspace is per-session scratch whose
 * rules are deleted together with it, so a setting saved there would vanish before the user could
 * benefit from it. Project-less sessions therefore read a default bucket instead:
 *  - a group-backed login (e.g. Easy Home's family) shares one bucket across its members, so the
 *    owner configures the policy once and every member's session evaluates it;
 *  - a group-less login falls back to a personal default bucket.
 */
object PermissionScope {
    private const val USER_DEFAULT_PREFIX = "user-default:"
    private const val GROUP_DEFAULT_PREFIX = "group-default:"

    /** Matches [com.easy.easyai.core.agent.AgentContext]'s shared read-only layer. */
    private const val SYSTEM_OWNER = "system"

    /** Rule-store key for [userId]'s personal project-less defaults. */
    fun userDefault(userId: String): String = "$USER_DEFAULT_PREFIX$userId"

    /** Rule-store key for a group's shared project-less defaults. */
    fun groupDefault(groupOwnerId: String): String = "$GROUP_DEFAULT_PREFIX$groupOwnerId"

    /** Whether [scopeId] addresses a default bucket rather than a project. */
    fun isDefaultScope(scopeId: String): Boolean =
        scopeId.startsWith(USER_DEFAULT_PREFIX) || scopeId.startsWith(GROUP_DEFAULT_PREFIX)

    /** Whether [scopeId] is a group's shared default bucket. */
    fun isGroupDefault(scopeId: String): Boolean = scopeId.startsWith(GROUP_DEFAULT_PREFIX)

    /**
     * Default bucket for a project-less session of [userId].
     *
     * [owners] is the chat boundary's read-visibility set — caller, group bucket(s), `system`
     * (see [com.easy.easyai.core.agent.AgentContext.effectiveOwners]). The first entry that is
     * neither the caller nor `system` is the group bucket, so a family shares one policy while a
     * group-less login keeps its own.
     *
     * Single-group assumption: [owners] is an unordered set, so "the first group entry" is only
     * meaningful when at most one group bucket is present. A product that lets one login act under
     * several groups must pass the active group explicitly instead of relying on this method.
     */
    fun defaultFor(owners: Collection<String>, userId: String?): String? {
        if (userId == null) return null
        val groupOwner = owners.firstOrNull { it != userId && it != SYSTEM_OWNER }
        return if (groupOwner != null) groupDefault(groupOwner) else userDefault(userId)
    }

    /** Rule-store key a call running in [projectId] must use. */
    fun forProject(
        projectId: String,
        projectKind: ProjectKind,
        userId: String?,
        owners: Collection<String> = emptyList()
    ): String =
        if (projectKind == ProjectKind.TEMP) defaultFor(owners, userId) ?: projectId else projectId
}
