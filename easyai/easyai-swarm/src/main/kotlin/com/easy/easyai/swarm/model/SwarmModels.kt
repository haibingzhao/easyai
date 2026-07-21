package com.easy.easyai.swarm.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.time.Instant

/**
 * Status of an individual task within a swarm run.
 */
enum class SwarmTaskStatus {
    PENDING,
    BLOCKED,
    IN_PROGRESS,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Status of an entire swarm run.
 */
enum class SwarmRunStatus {
    PENDING,
    RUNNING,
    PAUSED,
    RESUMING,
    COMPLETED,
    FAILED,
    CANCELLED
}

/**
 * Type of swarm task: single agent or multi-agent deliberation.
 */
enum class TaskType {
    @JsonProperty("single")
    SINGLE,
    @JsonProperty("deliberation")
    DELIBERATION,
    @JsonProperty("team")
    TEAM;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): TaskType =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown TaskType: $value")
    }
}

/**
 * Speaker ordering within a DeliberationGroup.
 */
enum class DeliberationOrder {
    @JsonProperty("sequential")
    SEQUENTIAL,
    @JsonProperty("round_robin")
    ROUND_ROBIN;

    companion object {
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String): DeliberationOrder =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) || it.jsonName().equals(value, ignoreCase = true) }
                ?: throw IllegalArgumentException("Unknown DeliberationOrder: $value")

        private fun DeliberationOrder.jsonName(): String = when (this) {
            SEQUENTIAL -> "sequential"
            ROUND_ROBIN -> "round_robin"
        }
    }
}

/**
 * MCP server binding for an inline custom swarm agent.
 * Mirrors the agent-level MCP binding semantics:
 * - Empty [toolNames] = all tools from this server are allowed.
 * - Empty [promptNames] = all prompts from this server are allowed.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SwarmMcpBinding(
    val serverName: String,
    val toolNames: List<String> = emptyList(),
    val promptNames: List<String> = emptyList(),
)

/**
 * Specification for a swarm agent role.
 *
 * Supports two modes:
 * - **Global agent**: [agentDefinitionId] is non-blank; tools, skills, system prompt, and model config
 *   are loaded from the AgentDefinition in DB.
 * - **Inline custom agent**: [agentDefinitionId] is blank; [name], [description], [systemPrompt],
 *   [toolNames], and [mcpConfigs] define the agent behavior directly within the preset.
 *
 * Note: `outputSchema` was removed in favor of AgentDefinition.outputSchema.
 * Jackson silently ignores the old field on deserialization for backward compatibility.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SwarmAgentSpec(
    val id: String,
    /** Agent definition ID. Required for global agents; blank for inline custom agents. */
    val agentDefinitionId: String = "",
    val role: String,
    val maxIterations: Int = 50,
    val timeoutSeconds: Int = 300,
    /** Override model name (e.g. "gpt-4o"). Null = inherit from agent definition or system default. */
    val modelName: String? = null,
    val maxRetries: Int = 2,
    // --- Inline custom agent fields (used when agentDefinitionId is blank) ---
    /** Display name for the inline agent. */
    val name: String = "",
    /** Description of the inline agent's purpose. */
    val description: String = "",
    /** System prompt template (Jinja2). Becomes the agent's promptTemplate. */
    val systemPrompt: String = "",
    /** Tool names available to this inline agent. */
    val toolNames: List<String> = emptyList(),
    /** MCP server bindings available to this inline agent. */
    val mcpConfigs: List<SwarmMcpBinding> = emptyList(),
) {
    /** Whether this spec defines an inline custom agent (no global AgentDefinition reference). */
    @get:JsonIgnore
    val isInline: Boolean get() = agentDefinitionId.isBlank()

    /** Cache key for looking up the resolved AgentDefinition in agentDefCache. */
    @get:JsonIgnore
    val cacheKey: String get() = if (isInline) id else agentDefinitionId
}

/**
 * A single task within a swarm DAG.
 * Can be a SINGLE agent task, a DELIBERATION group task, or a TEAM coordination task.
 */
data class SwarmTask(
    val id: String,
    /** Agent ID for SINGLE tasks. For DELIBERATION/TEAM tasks, this is ignored. */
    val agentId: String = "",
    /** Task-level prompt rendered as UserMessage. Does not affect the Agent's SystemMessage. */
    val promptTemplate: String = "",
    val dependsOn: List<String> = emptyList(),
    /** Map of variable_name → upstream_task_id for injecting upstream summaries. */
    val inputFrom: Map<String, String> = emptyMap(),
    val type: TaskType = TaskType.SINGLE,
    /** Deliberation spec — required when type=DELIBERATION. */
    val deliberation: DeliberationSpec? = null,
    /** Team spec — required when type=TEAM. */
    val team: TeamSpec? = null,
    /** Maximum retries for this task. */
    val maxRetries: Int = 2,
    /** Variable names that this task's agent can update at runtime. Empty = no updates allowed. */
    val updatableVariables: List<String> = emptyList(),
    /** Whether to use the Agent's promptTemplate as SystemMessage for SINGLE tasks. Default true.
     *  When false and no systemPromptTemplate is set, no system prompt is used at all. */
    val agentPromptEnabled: Boolean = true,
    /** Optional task-level additional system prompt for SINGLE tasks.
     *  When non-blank and agentPromptEnabled=true, appended after Agent's promptTemplate.
     *  When non-blank and agentPromptEnabled=false, used as the sole SystemMessage. */
    val systemPromptTemplate: String = "",
    /** Whether the agent must explicitly report task success/failure via report_task_result tool.
     *  Only applicable to SINGLE tasks. Default false = no report required (backward compatible). */
    val reportEnabled: Boolean = false,
    // Mutable runtime state
    var status: SwarmTaskStatus = SwarmTaskStatus.PENDING,
    var blockedBy: List<String> = emptyList(),
    var summary: String? = null,
    var error: String? = null,
    var startedAt: Instant? = null,
    var completedAt: Instant? = null,
    var workerIterations: Int = 0,
    var inputTokens: Long = 0,
    var outputTokens: Long = 0,
    var cacheReadTokens: Long = 0,
    var cacheWriteTokens: Long = 0,
    var durationMs: Long = 0,
    /** Deliberation history — populated during execution for DELIBERATION tasks. */
    @JsonIgnore
    var deliberationHistory: List<DeliberationEntry> = emptyList(),
    /** Verdict prompt sent to the Judge for final verdict — populated for DELIBERATION tasks. */
    @JsonIgnore
    var verdictPrompt: String? = null,
    /** Verdict response from the Judge — populated for DELIBERATION tasks. */
    @JsonIgnore
    var verdictResponse: String? = null,
    /** Team escalation history — populated during execution for TEAM tasks. */
    @JsonIgnore
    var escalationHistory: List<EscalationEntry> = emptyList(),
    /** Team member executions — populated during execution for TEAM tasks. */
    @JsonIgnore
    var memberExecutions: List<TeamMemberExecution> = emptyList(),
    /** Team round records — populated during execution for TEAM tasks. */
    @JsonIgnore
    var roundRecords: List<TeamRoundRecord> = emptyList()
) {
    /**
     * Apply a [WorkerResult] to this task, copying status, summary, token counts, and duration.
     */
    fun applyResult(result: WorkerResult) {
        status = result.status
        summary = result.summary
        workerIterations = result.iterations
        inputTokens = result.inputTokens
        outputTokens = result.outputTokens
        cacheReadTokens = result.cacheReadTokens
        cacheWriteTokens = result.cacheWriteTokens
        durationMs = result.durationMs
    }
}

/**
 * Multi-agent collaboration configuration: debate, review, refinement, consensus building, etc.
 * Embedded within a single DAG node (SwarmTask with type=DELIBERATION).
 *
 * The Judge acts as an Orchestrator that dynamically generates prompts for participants at runtime.
 * Users only need to configure participants, judge, and the deliberation context template.
 *
 * @param participants Agent IDs that participate in the deliberation.
 * @param judge Agent ID that acts as orchestrator and renders the final verdict.
 * @param maxRounds Maximum number of deliberation rounds before forced conclusion.
 * @param order Speaker ordering strategy.
 * @param contextTemplate Jinja2 template for the deliberation context/topic. Supports workflow variables.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class DeliberationSpec(
    val participants: List<String>,
    val judge: String,
    val maxRounds: Int = 3,
    val order: DeliberationOrder = DeliberationOrder.SEQUENTIAL,
    val contextTemplate: String = "",
)

/**
 * A single entry in a deliberation's interaction history.
 * Records one participant's response in one round.
 *
 * The first entry of each round may carry Judge-generated prompt metadata:
 * - [openingPrompt]: set on the first entry of Round 1 (shared by all participants).
 * - [roundPrompts]: set on the first entry of Round 2+ (per-participant personalized prompts).
 */
data class DeliberationEntry(
    val agentId: String,
    val round: Int,
    val response: String,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val durationMs: Long = 0,
    /** Opening prompt generated by the Judge for Round 1 — only set on the first entry of Round 1. */
    val openingPrompt: String? = null,
    /** Per-participant prompts generated by the Judge for Round 2+ — only set on the first entry of the round. */
    val roundPrompts: Map<String, String>? = null,
)

/**
 * API response wrapper for deliberation history, including the Judge's verdict prompt and response.
 */
data class DeliberationHistoryResponse(
    val entries: List<DeliberationEntry>,
    val verdictPrompt: String? = null,
    val verdictResponse: String? = null,
)

/**
 * Team coordination configuration for a swarm task.
 * Embedded within a DAG node (SwarmTask with type=TEAM).
 *
 * The Leader auto-generates planning and coordination prompts at runtime
 * from [contextTemplate], eliminating the need for manual prompt configuration.
 * Members use an injected EscalationTool for explicit escalation instead of keyword detection.
 *
 * @param leader Agent ID that coordinates task delegation and progress tracking.
 * @param members Agent IDs available for task execution (configuration-time candidate pool).
 * @param maxIterations Maximum coordination iterations before forced conclusion.
 * @param maxDynamicTasks Maximum dynamic tasks the leader can create.
 * @param roundTimeoutSeconds Timeout per round in seconds.
 * @param memberTimeoutSeconds Per-member execution timeout in seconds. 0 = auto (roundTimeout/2, min 30s).
 * @param contextTemplate Jinja2 template for the team task context/topic. Leader uses this to auto-generate prompts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TeamSpec(
    val leader: String,
    val members: List<String>,
    val maxIterations: Int = 5,
    val maxDynamicTasks: Int = 10,
    val roundTimeoutSeconds: Int = 600,
    val memberTimeoutSeconds: Int = 0,
    /** Jinja2 template for the team task context. Leader uses this to auto-generate prompts. */
    val contextTemplate: String = "",
)

/**
 * An escalation raised by a team member during execution.
 */
data class EscalationEntry(
    val memberId: String,
    val round: Int,
    val reason: String,
    val resolution: String? = null,
    val reassignedTo: String? = null,
)

/**
 * Execution record for a single team member within a team task.
 */
data class TeamMemberExecution(
    val memberId: String,
    val round: Int,
    val assignment: String,
    val status: MemberStatus,
    val summary: String? = null,
    val escalationReason: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
)

/**
 * Status of a team member's task execution.
 */
enum class MemberStatus {
    RUNNING,
    COMPLETED,
    ESCALATED,
    REASSIGNED
}

/**
 * Record of a single team coordination round (leader's decision history).
 *
 * Each round carries the full [leaderPrompt] sent to the Leader:
 * - Round 1: planning prompt (task context + member profiles + output format).
 * - Round 2+: coordination prompt (trigger events + running/completed members + escalation history).
 */
data class TeamRoundRecord(
    val round: Int,
    val leaderAnalysis: String,
    val delegatedMembers: List<String>,
    val completedMembers: List<String>,
    val escalations: List<String>,
    /** Full prompt sent to the Leader for this round — planning (round 1) or coordination (round 2+). */
    val leaderPrompt: String? = null,
)

/**
 * Runtime-only state for swarm execution.
 * Not serialized into snapshots — rebuilt on resume.
 */
data class SwarmRuntimeState(
    val currentTaskId: String? = null,
    val completedTaskIds: Set<String> = emptySet(),
    val taskTokenUsage: Map<String, Long> = emptyMap(),
    val resumeAfterTaskId: String? = null
)

/**
 * Swarm preset definition — loaded from YAML.
 * Contains agent specs, task definitions, and user-provided variables.
 */
data class SwarmPreset(
    val name: String,
    val title: String,
    val description: String = "",
    val agents: List<SwarmAgentSpec>,
    val tasks: List<SwarmTask>,
    val variables: List<SwarmVariable> = emptyList(),
    /** Preferred language for all LLM responses (e.g. "zh-CN", "en-US"). Empty = no language enforcement. */
    val language: String = ""
)

/**
 * User-provided variable for template rendering in preset prompts.
 */
data class SwarmVariable(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
    val defaultValue: String? = null,
    /** Whether this variable can be updated by agents at runtime via the update_variable tool. */
    val updatable: Boolean = false
)

/**
 * An active or completed swarm run — the execution aggregate root.
 */
data class SwarmRun(
    val id: String,
    val presetName: String,
    val title: String,
    var status: SwarmRunStatus = SwarmRunStatus.PENDING,
    val agents: List<SwarmAgentSpec>,
    val tasks: List<SwarmTask>,
    var userVars: MutableMap<String, String> = mutableMapOf(),
    val userId: String = "system",
    /** Preferred language for all LLM responses (e.g. "zh-CN", "en-US"). Empty = no language enforcement. */
    val language: String = "",
    val createdAt: Instant = Instant.now(),
    var startedAt: Instant? = null,
    var completedAt: Instant? = null,
    var totalInputTokens: Long = 0,
    var totalOutputTokens: Long = 0,
    var totalCacheReadTokens: Long = 0,
    var totalCacheWriteTokens: Long = 0,
    var totalDurationMs: Long = 0,
    var error: String? = null,
    @JsonIgnore
    val runtime: SwarmRuntimeState? = null,
    /** Variable definitions from the preset — used at runtime to determine which variables are updatable. */
    @JsonIgnore
    val variableDefinitions: List<SwarmVariable> = emptyList()
)

/**
 * Result of executing a single worker (agent or deliberation group).
 */
data class WorkerResult(
    val status: SwarmTaskStatus,
    val summary: String,
    val iterations: Int = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheWriteTokens: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
    /** Deliberation history — populated only for DELIBERATION tasks. */
    val deliberationHistory: List<DeliberationEntry> = emptyList(),
    /** Verdict prompt sent to the Judge — populated only for DELIBERATION tasks. */
    val verdictPrompt: String? = null,
    /** Verdict response from the Judge — populated only for DELIBERATION tasks. */
    val verdictResponse: String? = null,
    /** Team escalation history — populated only for TEAM tasks. */
    val escalationHistory: List<EscalationEntry> = emptyList(),
    /** Team member executions — populated only for TEAM tasks. */
    val memberExecutions: List<TeamMemberExecution> = emptyList(),
    /** Team round records — populated only for TEAM tasks. */
    val roundRecords: List<TeamRoundRecord> = emptyList(),
    /** Task completion report — populated when reportEnabled=true. */
    val taskReport: TaskReportResult? = null,
)

/**
 * Status reported by the agent via [com.easy.easyai.swarm.tool.TaskCompletionReportTool].
 */
enum class TaskReportStatus {
    SUCCESS,
    FAILED,
}

/**
 * Result of the task completion report tool call.
 * Populated by [com.easy.easyai.swarm.tool.TaskCompletionReportTool] and consumed by
 * [com.easy.easyai.swarm.runtime.SwarmRuntime] to determine final task status.
 */
data class TaskReportResult(
    val status: TaskReportStatus,
    val reason: String = "",
)
