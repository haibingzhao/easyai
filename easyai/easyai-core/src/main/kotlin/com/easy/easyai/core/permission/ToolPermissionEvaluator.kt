package com.easy.easyai.core.permission

import java.nio.file.Path

/**
 * Tool-specific permission evaluation strategy.
 * Each ToolBuilder provides its own evaluator, making permission
 * system open for extension but closed for modification.
 *
 * When a new tool is added, only its ToolBuilder needs to declare
 * the appropriate evaluator — PermissionService requires no changes.
 */
fun interface ToolPermissionEvaluator {
    fun evaluate(context: PermissionEvalContext): PermissionCheckResult
}

/**
 * Context passed to [ToolPermissionEvaluator.evaluate].
 *
 * @property rules Effective rules list (builder defaults + user rules)
 * @property projectPath Project root path for project-scoped checks
 * @property arguments Tool call arguments for pattern extraction
 * @property sharedEvaluator Hook to PermissionService's shared evaluation utilities
 */
data class PermissionEvalContext(
    val rules: List<PermissionRule>,
    val projectPath: Path?,
    val arguments: Map<String, Any?>,
    val sharedEvaluator: SharedPermissionEvaluator
)

/**
 * Shared evaluation utilities provided by PermissionService.
 * ToolPermissionEvaluator implementations call these for common patterns
 * (file read/write, shell, simple allow/deny).
 */
interface SharedPermissionEvaluator {
    fun evaluateFilePermission(
        rules: List<PermissionRule>,
        projectPath: Path?,
        arguments: Map<String, Any?>,
        read: Boolean
    ): PermissionCheckResult

    fun evaluateShellPermission(
        rules: List<PermissionRule>,
        projectPath: Path?,
        arguments: Map<String, Any?>
    ): PermissionCheckResult

    fun evaluateSimplePermission(
        permission: String,
        rules: List<PermissionRule>
    ): PermissionCheckResult
}
