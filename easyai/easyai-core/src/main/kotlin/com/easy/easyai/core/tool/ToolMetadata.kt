package com.easy.easyai.core.tool

/**
 * Immutable value object holding shared tool metadata.
 *
 * Serves as the single source of truth for properties that are identical between
 * [ToolBuilder] (static discovery) and [ToolDefinition] (runtime execution).
 *
 * Created once in the [ToolBuilder] and passed to the Tool via [BaseToolDefinition]'s
 * constructor, eliminating duplication and ensuring consistency.
 *
 * @property name Tool name identifier (e.g., "read", "write", "bash").
 * @property description Human-readable description for LLM tool selection.
 * @property permissionCategory Permission category for permission system mapping.
 * @property uiRenderer UI renderer identifier for frontend tool message rendering.
 * @property isDefaultTool Whether this tool should be included in the default agent's tool set.
 * @property patternKeys Argument keys used to extract a pattern for permission matching.
 * @property defaultPatternWildcard Whether the permission pattern should default to "*" when no patternKeys are found.
 * @property skipOnResume Whether this tool should be skipped when resuming a session.
 * @property tracksFileChanges Whether this tool modifies files on disk and should trigger snapshot tracking.
 * @property alwaysInclude Whether this tool bypasses agent-level toolNames filtering.
 *   Session-scoped coordination tools (e.g., team tools) set this to true so they are
 *   always available when their builder produces them, regardless of agentDef.toolNames.
 */
data class ToolMetadata(
    val name: String,
    val description: String,
    val permissionCategory: String = name,
    val uiRenderer: String = "generic",
    val isDefaultTool: Boolean = true,
    val patternKeys: List<String> = listOf("command", "cmd", "path", "file", "filepath", "file_path", "url"),
    val defaultPatternWildcard: Boolean = true,
    val skipOnResume: Boolean = false,
    val tracksFileChanges: Boolean = false,
    val alwaysInclude: Boolean = false
)
