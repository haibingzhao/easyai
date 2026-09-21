package com.easy.easyai.core.permission

/**
 * Result of a permission check evaluation.
 *
 * @property action The determined action (ALLOW, ASK, or DENY)
 * @property permission The permission type that was evaluated
 * @property pattern The pattern that was matched
 * @property reason Optional human-readable explanation, e.g. AI risk assessment reason
 */
data class PermissionCheckResult(
    val action: PermissionAction,
    val permission: String,
    val pattern: String,
    val reason: String? = null
)
