package com.easy.easyai.core.permission

/**
 * Result of a permission check evaluation.
 *
 * @property action The determined action (ALLOW, ASK, or DENY)
 * @property permission The permission type that was evaluated
 * @property pattern The pattern that was matched
 */
data class PermissionCheckResult(
    val action: PermissionAction,
    val permission: String,
    val pattern: String
)
