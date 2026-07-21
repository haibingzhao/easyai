package com.easy.easyai.core.permission

/**
 * A permission rule that determines how a tool call should be handled.
 *
 * @property permission The permission type, e.g., "tool.execute.shell", "tool.execute.edit"
 * @property pattern The pattern to match against (e.g., command string, file path), supports * and ? wildcards
 * @property action The action to take when the rule matches
 */
data class PermissionRule(
    val permission: String,
    val pattern: String,
    val action: PermissionAction
)
