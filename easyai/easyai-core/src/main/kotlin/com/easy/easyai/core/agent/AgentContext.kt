package com.easy.easyai.core.agent

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.memory.MemoryAccessTracker
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.prompt.InstructionInfo
import com.easy.easyai.core.tool.ToolDefinition
import java.nio.file.Path

/**
 * Unified agent context containing identity, behavior configuration, and runtime information.
 *
 * Built when a request arrives and flows through all system layers (SessionManager, Agent, AgentLoop, persistence);
 * any layer can retrieve the information it needs from this context.
 *
 * @param agentId The agent ID from the current chat request.
 * @param modelConfig The complete model provider configuration (single source of truth for model settings).
 *   When null, [modelId] and [protocol] will derive from fallback defaults.
 * @param sessionId Optional session ID for multi-turn conversations.
 * @param userId Optional user ID for authenticated requests (reserved for future use).
 * @param projectPath Optional resolved project path for project-scoped tool working directories.
 * @param parentAgentId The calling agent's ID. Null means top-level invocation (user-selected agent).
 *   Tracks call chain depth: primary -> subagent -> subagent, etc.
 * @param agentRunId Optional scope ID for todo/task tracking. Null for the main agent;
 *   non-null for a sub-agent invocation, typically the parent toolCallId.
 * @param promptTemplate Jinja2 template from AgentDefinition, or null for default segment-based prompt.
 * @param customInstructions Custom instructions from agent definition or configuration.
 * @param skills Skills data for prompt template rendering (list of {name, description} maps).
 * @param allowedSkillNames Skill whitelist for this agent. Empty = all skills allowed. Non-empty = only listed skills.
 * @param subAgents Sub-agents data for prompt template rendering (list of {name, description} maps).
 * @param tools The list of tool definitions available to the agent.
 * @param maxIterations Maximum number of agent loop iterations.
 * @param maxRetries Maximum number of retries for LLM call failures.
 * @param initialMessages Initial messages to seed the agent transcript.
 * @param modelContextLength Total context window size of the current model in tokens.
 */
data class AgentContext(
    // Identity
    val agentId: String,
    val modelConfig: ModelProviderConfig? = null,
    val sessionId: String? = null,
    val userId: String? = null,
    val projectId: String? = null,
    val projectPath: Path? = null,
    /** Whether automatic memory generation (MemoryFlushAgent) is enabled for the current project. */
    val memoryAutoGeneration: Boolean = true,
    /** Calling agent ID. Null = top-level (user-selected). Tracks call chain depth. */
    val parentAgentId: String? = null,
    /** Invocation scope for todo/task tracking. Null = main agent session scope. */
    val agentRunId: String? = null,
    // Behavior config
    val promptTemplate: String? = null,
    val customInstructions: String? = null,
    val skills: List<Map<String, Any?>> = emptyList(),
    /** Skill whitelist for this agent. Empty = no skills allowed. Non-empty = only listed skills. */
    val allowedSkillNames: List<String> = emptyList(),
    val subAgents: List<Map<String, Any?>> = emptyList(),
    /** Team member data for prompt rendering (list of {id, name, description} maps). Only for TEAM agents. */
    val teamMembers: List<Map<String, Any?>> = emptyList(),
    /** Recovered team execution status summary (injected when restoring a TEAM session). */
    val teamStatusSummary: String? = null,
    /** Project-level instructions loaded from AGENTS.md. Injected into system prompt. */
    val instructions: List<InstructionInfo> = emptyList(),
    /**
     * Explicit MCP server bindings that override DB-based resolution.
     * Empty = resolve MCP configs from the agent_tool table via [agentId] (default path).
     * Non-empty = use these bindings directly (e.g. inline custom swarm agents that have no DB row).
     */
    val mcpConfigs: List<AgentToolConfig> = emptyList(),
    val tools: List<ToolDefinition> = emptyList(),
    val maxIterations: Int = 100,
    val maxRetries: Int = 3,
    val initialMessages: List<EasyAiMessage> = emptyList(),
    val modelContextLength: Int = 204_800,  // Default context window size (200K)
    /** JSON Schema for structured output enforcement. Injected into ChatOptions and system prompt. */
    val outputSchema: String? = null,
    /** JSON Schema for structured input validation. Validated against inputVariables before agent loop. */
    val inputSchema: String? = null,
    /** Structured input data validated against inputSchema. Empty for follow-up messages. */
    val inputVariables: Map<String, Any?> = emptyMap(),
    // Swarm context
    /** Swarm run ID when executing as a swarm worker. Null for normal sessions. */
    val swarmRunId: String? = null,
    /** Swarm task ID within the current run. Null for normal sessions. */
    val swarmTaskId: String? = null,
    /** Per-session tracker for memory entries accessed by memory_read / memory_search tools. */
    val memoryAccessTracker: MemoryAccessTracker = MemoryAccessTracker(),
    /** Mutable session-scoped variables. Survives compaction, persisted to DB for resume. */
    val sessionVariables: SessionVariables = SessionVariables(),
    /**
     * Abort signal propagated from the parent ChatSession.
     * Returns true when the user has requested cancellation (e.g., via Stop button).
     * Sub-agents inherit this from the parent to enable graceful abort at loop checkpoints.
     */
    val abortSignal: () -> Boolean = { false },
    /**
     * Dry-run mode: skip all persistence (messages, todos, snapshots).
     * Used by Config Generator Agent and other ephemeral agents that don't need DB writes.
     */
    val dryRun: Boolean = false,
    /** Environment variables for script LLM access. Non-empty when script-llm feature is enabled. */
    val scriptEnv: Map<String, String> = emptyMap()
) {
    /** Model ID derived from modelConfig, or empty string if not configured. */
    val modelId: String get() = modelConfig?.modelId ?: ""

    /** Config ID derived from modelConfig, or empty string if not configured. */
    val configId: String get() = modelConfig?.id ?: ""

    /** Protocol name derived from modelConfig, or null if not configured. */
    val protocol: String? get() = modelConfig?.protocol?.name
}
