package com.easy.easyai.repository.database

import org.jetbrains.exposed.v1.core.Table

/**
 * Exposed table definitions for EasyAI storage.
 * Using Exposed 1.2.0 with v1 API.
 *
 * IMPORTANT: For persistent databases (H2 file, PostgreSQL), DDL is managed by
 * Flyway migration scripts at `db/migration/V*.sql` in easyai-r2dbc-autoconfigure.
 * This file serves as the Exposed query mapping only.
 *
 * Convention: When adding/modifying columns, you MUST:
 * 1. Update this file (query mapping)
 * 2. Create a new `V{n}__description.sql` migration script (DDL authority)
 */
object Tables {

    /**
     * User account table.
     * passwordHash stores BCrypt hash (never plaintext).
     * avatar stores a preset avatar ID string (e.g. "avatar-1").
     */
    object UserTable : Table("app_user") {
        val id = varchar("id", 255)
        val username = varchar("username", 128).uniqueIndex()
        val displayName = varchar("display_name", 255)
        val email = varchar("email", 255).nullable()
        val passwordHash = varchar("password_hash", 256)
        val avatar = varchar("avatar", 64).default("avatar-1")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val primaryKey = PrimaryKey(id)
    }

    /**
     * Refresh token table. Stores SHA-256 hash of the token (never the raw value).
     * Used for token rotation: each refresh deletes old and inserts new.
     */
    object RefreshTokenTable : Table("refresh_token") {
        val id = varchar("id", 255)
        val userId = varchar("user_id", 255)
        val tokenHash = varchar("token_hash", 256)
        val expiresAt = long("expires_at")
        val createdAt = long("created_at")
        override val primaryKey = PrimaryKey(id)
        init {
            index(false, userId)
            index(false, tokenHash)
        }
    }

    /**
     * Agent definition table. Each agent is a reusable template for chat sessions.
     * Agent defines behavior: name, agent_type (PRIMARY/SUBAGENT/ALL),
     * custom instructions, prompt template, and enabled tools.
     * Model configuration comes from Session's reference to model_provider_config.
     */
    object AgentTable : Table("agent") {
        val id = varchar("id", 255)
        val name = varchar("name", 255)
        val agentType = varchar("agent_type", 32).default("PRIMARY")
        val agentContext = varchar("agent_context", 32).default("CHAT")
        val description = text("description").nullable()
        val promptTemplate = text("prompt_template").nullable()
        val customInstructions = text("custom_instructions").nullable()
        val maxIterations = integer("max_iterations").default(50)
        val maxSubAgentDepth = integer("max_subagent_depth").default(1)
        val color = varchar("color", 32).nullable()
        val enabled = bool("enabled").default(true)
        val instructionsEnabled = bool("instructions_enabled").default(true)
        val inputSchema = text("input_schema").nullable()
        val outputSchema = text("output_schema").nullable()
        val outputSchemaMultiTurn = bool("output_schema_multi_turn").default(false)
        val userId = varchar("user_id", 255).default("system")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id, userId)
    }

    /**
     * Agent whitelist table. Pure whitelist model: a row means "allowed", absence means "not allowed".
     *
     * Supports two target types:
     * - TOOL: which tools an agent can use
     * - SUBAGENT: which sub-agents a primary agent can invoke
     *
     * An empty result for an agent means "inherit all" (primary) or "inherit parent tools" (sub-agent).
     */
    object AgentToolTable : Table("agent_tool") {
        val id = varchar("id", 255)
        val agentId = varchar("agent_id", 255)
        val targetType = varchar("target_type", 16).default("TOOL")
        val targetName = varchar("target_name", 128).default("")
        val metadata = text("metadata").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            // Index for high-frequency agentId lookups
            index(false, agentId)
        }
    }

    /**
     * User-defined slash command table.
     * Each user can define their own commands with templates.
     * userId isolation: users only see their own + system commands.
     */
    object UserCommandTable : Table("user_command") {
        val id = varchar("id", 255)
        val name = varchar("name", 128)
        val description = text("description").nullable()
        val aliases = text("aliases").nullable()        // JSON array: ["alias1","alias2"]
        val template = text("template").nullable()       // markdown template body
        val hints = text("hints").nullable()             // JSON array: ["$1","$ARGUMENTS"]
        val userId = varchar("user_id", 255).default("system")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        override val primaryKey = PrimaryKey(id)
        init {
            index(false, userId)
            uniqueIndex(userId, name)  // unique command name per user
        }
    }

    object Project : Table("project") {
        val id = varchar("id", 255)
        val name = varchar("name", 255)
        val path = varchar("path", 512)
        val description = text("description").nullable()
        val userId = varchar("user_id", 255).default("system")
        val memoryAutoGeneration = bool("memory_auto_generation").default(true)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)

        init {
            // Composite unique: same path is allowed for different users
            uniqueIndex(path, userId)
        }
    }

    object Session : Table("session") {
        val id = varchar("id", 255)
        val projectId = varchar("project_id", 255).nullable()
        val title = varchar("title", 255).default("New Chat")
        val status = varchar("status", 32).default("active")
        val pendingPermission = text("pending_permission").nullable()
        val userId = varchar("user_id", 255).default("system")
        val swarmRunId = varchar("swarm_run_id", 255).nullable()
        val swarmTaskId = varchar("swarm_task_id", 255).nullable()
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")
        /** Dirty marker bumped only by updateMessage() — used for incremental fetch fallback. */
        val contentUpdatedAt = long("content_updated_at").default(0L)
        /** Why the last agent execution ended: "normal", "max_iterations", "cancelled", "error". Cleared on each new run. */
        val endReason = varchar("end_reason", 32).nullable()
        /** JSON-serialized GoalState when a /goal is active; null when no goal is set. */
        val goalJson = text("goal_json").nullable()

        override val primaryKey = PrimaryKey(id)
    }

    object Message : Table("message") {
        val id = varchar("id", 255)
        val sessionId = varchar("session_id", 255)
        val agentId = varchar("agent_id", 255).nullable()
        val configId = varchar("config_id", 255).nullable()
        val modelId = varchar("model_id", 255).nullable()
        val role = varchar("role", 32)
        val contentBlocks = text("content_blocks").nullable()
        val metadata = text("metadata").nullable()
        val inputTokenCount = integer("input_token_count").nullable()
        val outputTokenCount = integer("output_token_count").nullable()
        val cacheReadTokenCount = integer("cache_read_token_count").nullable()
        val cacheWriteTokenCount = integer("cache_write_token_count").nullable()
        val stopReason = varchar("stop_reason", 32).nullable()
        val compactedAt = long("compacted_at").nullable()
        val durationMs = long("duration_ms").nullable()
        val parentMessageId = varchar("parent_message_id", 255).nullable()
        val parentToolCallId = varchar("parent_tool_call_id", 255).nullable()
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            // Composite index for session-scoped queries: loadMessagesWithTimestamps,
            // loadActiveMessages, deleteMessagesFromTimestamp, getFirstCompactionAfter, etc.
            index(false, sessionId, createdAt)
        }
    }

    /**
     * Model config group table for grouping model configurations with shared connection settings.
     * A group stores protocol/baseUrl/apiKey; member configs denormalize these fields for zero-JOIN reads.
     */
    object ModelConfigGroupTable : Table("model_config_group") {
        val id = varchar("id", 255)
        val name = varchar("name", 255)
        val protocol = varchar("protocol", 64)
        val isCustom = bool("is_custom")
        val baseUrl = varchar("base_url", 512).nullable()
        val apiKey = text("api_key").nullable()
        val timeoutSeconds = long("timeout_seconds").default(600L)
        val userId = varchar("user_id", 255).default("system")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)
    }

    /**
     * Model provider configuration table for user-saved and pre-defined providers.
     * isCustom=false: pre-defined provider (OpenAI, Anthropic, etc.)
     * isCustom=true: user-saved provider configuration.
     */
    object ModelProviderConfigTable : Table("model_provider_config") {
        val id = varchar("id", 255)
        val name = varchar("name", 255)
        val protocol = varchar("protocol", 64)
        val isCustom = bool("is_custom")
        val baseUrl = varchar("base_url", 512).nullable()
        val apiKey = text("api_key").nullable()
        val modelId = varchar("model_id", 255)
        val modelName = varchar("model_name", 255).nullable()
        val isCustomModel = bool("is_custom_model")
        val enabled = bool("enabled")
        val options = text("options").nullable()  // JSON storage for ModelOptions
        val capabilities = text("capabilities").nullable()  // JSON storage for ModelCapabilities
        val timeoutSeconds = long("timeout_seconds").default(600L)
        val groupId = varchar("group_id", 255).nullable()  // FK → model_config_group.id
        val userId = varchar("user_id", 255).default("system")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)
    }

    /**
     * Todo items for session task tracking.
     * position is auto-maintained (equals list index).
     * agentRunId isolates todos per sub-agent invocation; null means the main agent's session-level todos.
     */
    object TodoTable : Table("todo") {
        val id = varchar("id", 255)
        val sessionId = varchar("session_id", 255)
        val agentRunId = varchar("agent_run_id", 255).nullable()
        val content = text("content")
        val status = varchar("status", 32)
        val priority = varchar("priority", 16)
        val position = integer("position")
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            // Composite index for high-frequency scoped lookups (getTodos, saveTodos, deleteTodos)
            index(false, sessionId, agentRunId)
        }
    }

    /**
     * Permission rules for tool execution authorization.
     * Rules are scoped per project and evaluated in creation order.
     */
    object PermissionRuleTable : Table("permission_rule") {
        val id = varchar("id", 255)
        val projectId = varchar("project_id", 255)
        val permission = varchar("permission", 255)
        val pattern = varchar("pattern", 512)
        val action = varchar("action", 32)
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            // Index for high-frequency projectId lookups
            index(false, projectId)
        }
    }

    /**
     * MCP server configuration table.
     * Stores user-configured MCP server connections (local process or remote HTTP).
     * type = "local": runs a local command via stdio transport
     * type = "remote": connects to a remote HTTP/SSE endpoint
     */
    object McpServerConfigTable : Table("mcp_server_config") {
        val id = varchar("id", 255)
        val name = varchar("name", 128)
        val type = varchar("type", 16)  // "local" | "remote"
        val command = text("command").nullable()   // JSON array, local only
        val env = text("env").nullable()            // JSON object
        val url = varchar("url", 512).nullable()   // remote only
        val headers = text("headers").nullable()   // JSON object, remote only
        val cwd = varchar("cwd", 512).nullable()    // working directory, local only
        val timeoutSeconds = long("timeout_seconds").default(120L)  // request timeout in seconds
        val enabled = bool("enabled").default(true)
        val userId = varchar("user_id", 255).default("system")
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)

        init {
            uniqueIndex(userId, name)  // unique MCP server name per user
        }
    }

    // ---- Swarm tables ----

    /**
     * Swarm run table. Each row represents a complete swarm execution.
     */
    object SwarmRunTable : Table("swarm_run") {
        val id = varchar("id", 255)
        val presetName = varchar("preset_name", 255)
        val title = varchar("title", 255)
        val status = varchar("status", 32)
        val agents = text("agents")              // JSON: List<SwarmAgentSpec>
        val userVars = text("user_vars")         // JSON: Map<String, String>
        val totalInputTokens = long("total_input_tokens").default(0)
        val totalOutputTokens = long("total_output_tokens").default(0)
        val totalCacheReadTokens = long("total_cache_read_tokens").default(0)
        val totalCacheWriteTokens = long("total_cache_write_tokens").default(0)
        val totalDurationMs = long("total_duration_ms").default(0)
        val error = text("error").nullable()
        val userId = varchar("user_id", 255).default("system")
        val createdAt = long("created_at")
        val startedAt = long("started_at").nullable()
        val completedAt = long("completed_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, createdAt)
            index(false, userId)
        }
    }

    /**
     * Swarm task table. Each row is a single task within a swarm run.
     */
    object SwarmTaskTable : Table("swarm_task") {
        val id = varchar("id", 255)
        val runId = varchar("run_id", 255)
        val taskId = varchar("task_id", 255)
        val agentId = varchar("agent_id", 255)
        val taskType = varchar("task_type", 32)  // SINGLE | DELIBERATION
        val status = varchar("status", 32)
        val summary = text("summary").nullable()
        val error = text("error").nullable()
        val workerIterations = integer("worker_iterations").default(0)
        val inputTokens = long("input_tokens").default(0)
        val outputTokens = long("output_tokens").default(0)
        val cacheReadTokens = long("cache_read_tokens").default(0)
        val cacheWriteTokens = long("cache_write_tokens").default(0)
        val durationMs = long("duration_ms").default(0)
        val startedAt = long("started_at").nullable()
        val completedAt = long("completed_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, runId)
        }
    }

    /**
     * Swarm deliberation history table.
     * Records each participant's response per round within a DeliberationGroup.
     * The first entry of each round may carry Judge-generated prompt metadata
     * in [openingPrompt] (Round 1) or [roundPrompts] (Round 2+, JSON-encoded).
     */
    object SwarmDeliberationHistoryTable : Table("swarm_deliberation_history") {
        val id = varchar("id", 255)
        val runId = varchar("run_id", 255)
        val taskId = varchar("task_id", 255)
        val agentId = varchar("agent_id", 255)
        val round = integer("round")
        val response = text("response")
        val inputTokens = long("input_tokens").default(0)
        val outputTokens = long("output_tokens").default(0)
        val cacheReadTokens = long("cache_read_tokens").default(0)
        val cacheWriteTokens = long("cache_write_tokens").default(0)
        val durationMs = long("duration_ms").default(0)
        val openingPrompt = text("opening_prompt").nullable()
        val roundPrompts = text("round_prompts").nullable()
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, runId, taskId)
        }
    }

    /**
     * Swarm deliberation verdict table.
     * Stores the Judge's verdict prompt and response for a DELIBERATION task.
     */
    object SwarmDeliberationVerdictTable : Table("swarm_deliberation_verdict") {
        val id = varchar("id", 255)
        val runId = varchar("run_id", 255)
        val taskId = varchar("task_id", 255)
        val verdictPrompt = text("verdict_prompt")
        val verdictResponse = text("verdict_response")
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(true, runId, taskId)
        }
    }

    /**
     * Swarm team escalation history table.
     * Records escalations raised by team members during TEAM task execution.
     */
    object SwarmEscalationHistoryTable : Table("swarm_escalation_history") {
        val id = varchar("id", 255)
        val runId = varchar("run_id", 255)
        val taskId = varchar("task_id", 255)
        val memberId = varchar("member_id", 255)
        val round = integer("round")
        val reason = text("reason")
        val resolution = text("resolution").nullable()
        val reassignedTo = varchar("reassigned_to", 255).nullable()
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, runId, taskId)
        }
    }

    /**
     * Swarm team member execution table.
     * Records each member's assignment, status, and token usage per round in a TEAM task.
     */
    object SwarmTeamMemberExecutionTable : Table("swarm_team_member_execution") {
        val id = varchar("id", 255)
        val runId = varchar("run_id", 255)
        val taskId = varchar("task_id", 255)
        val memberId = varchar("member_id", 255)
        val round = integer("round")
        val assignment = text("assignment")
        val status = varchar("status", 32)
        val summary = text("summary").nullable()
        val escalationReason = text("escalation_reason").nullable()
        val inputTokens = long("input_tokens").default(0L)
        val outputTokens = long("output_tokens").default(0L)
        val memberSessionId = varchar("member_session_id", 64).nullable()
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, runId, taskId)
        }
    }

    /**
     * Swarm team round record table.
     * Records the leader's analysis, delegation decisions, and full prompt per coordination round.
     * delegatedMembers, completedMembers, escalations are stored as JSON-serialized List<String>.
     */
    object SwarmTeamRoundRecordTable : Table("swarm_team_round_record") {
        val id = varchar("id", 255)
        val runId = varchar("run_id", 255)
        val taskId = varchar("task_id", 255)
        val round = integer("round")
        val leaderAnalysis = text("leader_analysis")
        val delegatedMembers = text("delegated_members")     // JSON: List<String>
        val completedMembers = text("completed_members")     // JSON: List<String>
        val escalations = text("escalations")                // JSON: List<String>
        /** Full prompt sent to the Leader — planning (round 1) or coordination (round 2+). */
        val leaderPrompt = text("leader_prompt").nullable()
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, runId, taskId)
        }
    }

    /**
     * Team Agent member execution table.
     * Records each member's assignment, status, and token usage per round in a Team Agent session.
     * Keyed by team_session_id (the leader's chat session) — independent from Swarm tables.
     */
    object TeamMemberExecutionTable : Table("team_member_execution") {
        val id = varchar("id", 64)
        val teamSessionId = varchar("team_session_id", 64)
        val memberId = varchar("member_id", 128)
        val round = integer("round").default(1)
        val assignment = text("assignment")
        val status = varchar("status", 20)
        val summary = text("summary").nullable()
        val escalationReason = text("escalation_reason").nullable()
        val memberSessionId = varchar("member_session_id", 64).nullable()
        val toolCallId = varchar("tool_call_id", 128).nullable()
        val inputTokens = long("input_tokens").default(0L)
        val outputTokens = long("output_tokens").default(0L)
        val startedAt = long("started_at").nullable()
        val completedAt = long("completed_at").nullable()

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, teamSessionId)
        }
    }

    /**
     * Team Agent round record table.
     * Records which members were delegated/completed/blocked/resumed per coordination round.
     * Member lists are stored as JSON-serialized List<String>.
     */
    object TeamRoundRecordTable : Table("team_round_record") {
        val id = varchar("id", 64)
        val teamSessionId = varchar("team_session_id", 64)
        val round = integer("round")
        val delegatedMembers = text("delegated_members").nullable()   // JSON: List<String>
        val completedMembers = text("completed_members").nullable()   // JSON: List<String>
        val blockedMembers = text("blocked_members").nullable()       // JSON: List<String>
        val resumedMembers = text("resumed_members").nullable()       // JSON: List<String>
        val createdAt = long("created_at")

        override val primaryKey = PrimaryKey(id)

        init {
            index(false, teamSessionId)
        }
    }

    /**
     * Swarm preset table. Stores reusable swarm workflow definitions.
     */
    object SwarmPresetTable : Table("swarm_preset") {
        val id = varchar("id", 255)
        val name = varchar("name", 255)
        val title = varchar("title", 255)
        val description = text("description").default("")
        val agentsJson = text("agents_json")       // JSON: List<SwarmAgentSpec>
        val tasksJson = text("tasks_json")          // JSON: List<SwarmTask>
        val variablesJson = text("variables_json")   // JSON: List<SwarmVariable>
        val language = varchar("language", 16).default("")
        val userId = varchar("user_id", 255).default("system")
        val enabled = bool("enabled").default(true)
        val createdAt = long("created_at")
        val updatedAt = long("updated_at")

        override val primaryKey = PrimaryKey(id)

        init {
            uniqueIndex(userId, name)
        }
    }
}
