package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.ToolPermissionEvaluator

/**
 * Builder interface for creating Tool instances.
 * 
 * ToolBuilder serves as the single source of truth for static tool metadata
 * (name, description, permissionCategory, etc.) and provides a factory method
 * to create ToolDefinition instances at runtime.
 * 
 * Each ToolBuilder is a Spring Bean discovered automatically via DI.
 * Metadata can be queried without creating Tool instances, which is useful for:
 * - AgentSeedInitializer (determining default tools)
 * - ToolRegistry (listing available tools for UI)
 * - PermissionService (building tool -> permission category cache)
 */
interface ToolBuilder {
    // --- Static metadata (single source of truth) ---

    /** Shared tool metadata — the single source of truth for properties common to Builder and Tool. */
    val metadata: ToolMetadata

    // --- Delegated metadata properties (convenience accessors) ---

    /** Tool name identifier (e.g., "read", "write", "bash"). */
    val name: String get() = metadata.name

    /** Human-readable description for LLM tool selection. */
    val description: String get() = metadata.description

    /** Permission category for permission system mapping. */
    val permissionCategory: String get() = metadata.permissionCategory

    /** UI renderer identifier for frontend tool message rendering. */
    val uiRenderer: String get() = metadata.uiRenderer

    /** Whether this tool should be included in the default agent's tool set. */
    val isDefaultTool: Boolean get() = metadata.isDefaultTool

    /** Argument keys used to extract a pattern for permission matching. */
    val patternKeys: List<String> get() = metadata.patternKeys

    /** Whether the permission pattern should default to "*" when no patternKeys are found. */
    val defaultPatternWildcard: Boolean get() = metadata.defaultPatternWildcard

    /**
     * Whether this tool should be skipped when resuming a session.
     * Tools that return WaitForUserContent (like ask_question) should set this to true,
     * as they represent pending user interactions, not tool calls that need re-execution.
     * Default: false (tool will be re-executed on resume).
     */
    val skipOnResume: Boolean get() = metadata.skipOnResume

    /**
     * Whether this tool modifies files on disk and should trigger snapshot tracking.
     * When true, the snapshot system will capture file changes after this tool executes.
     * Default: false (read-only or non-file-modifying tools).
     */
    val tracksFileChanges: Boolean get() = metadata.tracksFileChanges

    /**
     * Whether this tool bypasses agent-level toolNames filtering.
     * Default: false (subject to toolNames filtering).
     */
    val alwaysInclude: Boolean get() = metadata.alwaysInclude

    /**
     * Permission evaluator for this tool.
     * Determines how permission checks are performed for this tool's calls.
     * Default: simple evaluation using "tool.execute.$permissionCategory".
     */
    val permissionEvaluator: ToolPermissionEvaluator
        get() = ToolPermissionEvaluator { ctx ->
            ctx.sharedEvaluator.evaluateSimplePermission(
                "tool.execute.$permissionCategory", ctx.rules
            )
        }

    /**
     * Default permission rules applied when no user rules override.
     * These rules are prepended to user rules from the database.
     * Override to provide tool-specific defaults (e.g., file.read.project ALLOW).
     */
    val defaultPermissionRules: List<PermissionRule> get() = emptyList()

    /**
     * Whether this tool is restricted to the main agent (no sub-agents).
     * When true, sub-agents will be blocked from using this tool.
     * Default: false (available to all agents).
     */
    val mainAgentOnly: Boolean get() = false

    /**
     * The permission string used for saving "always allow/deny" rules.
     * Maps to the permission type stored in the database when users
     * choose to remember their permission decision.
     * Default: "tool.execute.$permissionCategory".
     */
    val rulePermissionType: String get() = "tool.execute.$permissionCategory"

    // --- Runtime creation ---

    /**
     * Create a ToolDefinition instance using the given context.
     * 
     * @param context Agent context providing identity, project path, session info, etc.
     * @param agentService Agent service providing infrastructure dependencies.
     * @return Configured ToolDefinition instance, or null if this tool is not applicable
     *         for the current context (e.g., no session, missing dependencies).
     */
    fun build(context: AgentContext, agentService: AgentService): ToolDefinition?
}
