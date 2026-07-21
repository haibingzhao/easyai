package com.easy.easyai.core.permission

/**
 * Permission action types for tool execution authorization.
 * - ALLOW: Tool execution is permitted
 * - ASK: User must be asked for permission
 * - DENY: Tool execution is forbidden
 */
enum class PermissionAction {
    ALLOW,
    ASK,
    DENY
}
