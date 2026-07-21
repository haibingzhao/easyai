package com.easy.easyai.swarm.runtime

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentEnv
import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.SubAgentContextResolver
import com.easy.easyai.core.agent.TargetType
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.swarm.model.SwarmAgentSpec
import com.easy.easyai.swarm.model.SwarmMcpBinding
import com.easy.easyai.swarm.model.SwarmRun
import com.easy.easyai.swarm.model.SwarmTask
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory

/**
 * Resolves full [AgentContext] for swarm worker agents by loading [AgentDefinition]
 * from DB and delegating tool/skill/MCP resolution to [SubAgentContextResolver].
 *
 * Provides batch pre-loading of AgentDefinitions and model config caching
 * for run-level performance optimization.
 */
class SwarmAgentResolver(
    private val agentStore: AsyncAgentStore,
    private val contextResolver: SubAgentContextResolver,
    private val templateRenderer: TemplateRenderer,
    private val modelConfigStore: ModelProviderConfigStore? = null,
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Resolve a single swarm agent spec into a fully-populated [AgentContext] and tool list.
     *
     * Supports two modes:
     * - Global agent: loads [AgentDefinition] from DB via [SwarmAgentSpec.agentDefinitionId].
     * - Inline custom agent: synthesizes an [AgentDefinition] from the spec's inline fields.
     *
     * @param spec The swarm agent specification (global or inline).
     * @param run The current swarm run (provides userId and swarm metadata).
     * @param task The current task being executed.
     * @param modelConfigId Optional model config ID override. When non-null, takes priority
     *   over [SwarmAgentSpec.modelName].
     * @param agentDefCache Optional pre-loaded agent definitions from batchResolve.
     * @param modelConfigCache Optional pre-loaded model configs to avoid repeated DB queries.
     * @return Pair of (AgentContext with tools/skills/MCP resolved, list of tool definitions).
     */
    suspend fun resolve(
        spec: SwarmAgentSpec,
        run: SwarmRun,
        task: SwarmTask,
        modelConfigId: String? = null,
        agentDefCache: Map<String, AgentDefinition> = emptyMap(),
        modelConfigCache: List<ModelProviderConfig>? = null,
        inputFromVars: Map<String, String> = emptyMap(),
        outputSchemaOverride: String? = null,
    ): Pair<AgentContext, List<ToolDefinition>> {
        // 1. Load AgentDefinition from cache/DB, or synthesize from inline spec
        val agentDef = if (spec.isInline) {
            AgentDefinition.create(
                id = "inline-${spec.id}",
                name = spec.name.ifBlank { spec.id },
                description = spec.description,
                promptTemplate = spec.systemPrompt,
                toolNames = spec.toolNames,
                agentContext = AgentEnv.SWARM,
            )
        } else {
            agentDefCache[spec.agentDefinitionId]
                ?: agentStore.findById(spec.agentDefinitionId, run.userId)
                ?: throw IllegalArgumentException(
                    "AgentDefinition '${spec.agentDefinitionId}' not found for swarm agent '${spec.id}'"
                )
        }

        // 2. Resolve model config
        val modelConfig = resolveModelConfig(spec, modelConfigId, modelConfigCache, run.userId)

        // 3. Build a synthetic parent context for SubAgentContextResolver.
        // For inline agents, inject explicit MCP bindings (they have no agent_tool DB rows);
        // global agents resolve MCP from DB, so the override stays empty.
        val inlineMcpConfigs = if (spec.isInline) {
            spec.mcpConfigs.map { it.toAgentToolConfig(spec.id) }
        } else emptyList()
        val parentContext = AgentContext(
            agentId = "swarm-${run.id}",
            userId = run.userId,
            sessionId = null,
            parentAgentId = null,
            swarmRunId = run.id,
            swarmTaskId = task.id,
            mcpConfigs = inlineMcpConfigs,
        )

        // 4. Delegate to SubAgentContextResolver for tools/skills/MCP resolution
        val (resolvedContext, resolvedTools) = contextResolver.resolve(agentDef, parentContext)

        // 5. Build final worker context with swarm-specific overrides
        // Swarm workers have no skills — clear skills data and remove load_skill tool
        val swarmTools = resolvedTools.filter { it.name != "load_skill" }

        // Determine effective system prompt: support stacking Agent + Task prompts
        // Pre-render systemPromptTemplate with flat variables (userVars + inputFromVars)
        // because it uses {{ varName }} syntax, not {{ input.varName }}.
        val renderedSystemPrompt = if (task.systemPromptTemplate.isNotBlank()) {
            preRenderTemplate(task.systemPromptTemplate, run.userVars, inputFromVars)
        } else ""

        val effectivePromptTemplate = when {
            // Both ON: stack Agent prompt + pre-rendered Task additional prompt
            renderedSystemPrompt.isNotBlank() && task.agentPromptEnabled ->
                "${resolvedContext.promptTemplate}\n\n---\n\n${renderedSystemPrompt}"
            // Only task prompt (Agent disabled)
            renderedSystemPrompt.isNotBlank() ->
                renderedSystemPrompt
            // Agent disabled, no task prompt: no system prompt at all
            !task.agentPromptEnabled -> ""
            // Default: Agent's promptTemplate
            else -> resolvedContext.promptTemplate
        }

        // Inject language instruction at the end of the system prompt when configured
        val finalEffectivePromptTemplate = if (run.language.isNotBlank()) {
            effectivePromptTemplate + buildLanguageSegment(run.language)
        } else {
            effectivePromptTemplate
        }

        val workerContext = resolvedContext.copy(
            modelConfig = modelConfig,
            maxIterations = spec.maxIterations,
            maxRetries = spec.maxRetries,
            outputSchema = outputSchemaOverride ?: agentDef.outputSchema,
            swarmRunId = run.id,
            swarmTaskId = task.id,
            agentRunId="swarm-${run.id}-${task.id}",
            skills = emptyList(),
            allowedSkillNames = emptyList(),
            promptTemplate = finalEffectivePromptTemplate,
        )

        // 6. Parse inputSchema properties and map userVars to inputVariables
        val inputVariables = parseInputVariables(agentDef.inputSchema, run.userVars)
        val finalContext = workerContext.copy(inputVariables = inputVariables)

        logger.info(
            "Resolved swarm agent '{}': agentDef='{}', {} tools, model={}, {} inputVars",
            spec.id, agentDef.name, swarmTools.size, modelConfig?.modelId ?: "default",
            inputVariables.size
        )

        return finalContext to swarmTools
    }

    /**
     * Batch pre-load all AgentDefinitions for a swarm run to avoid repeated DB queries.
     *
     * @param specs All agent specs in the swarm run.
     * @param userId The user ID for scoping agent queries.
     * @return Map of agentDefinitionId → AgentDefinition.
     */
    suspend fun batchLoadAgentDefinitions(
        specs: List<SwarmAgentSpec>,
        userId: String,
    ): Map<String, AgentDefinition> {
        val uniqueIds = specs.filter { !it.isInline }.map { it.agentDefinitionId }.distinct()
        val result = agentStore.findByIds(uniqueIds, userId).toMutableMap()

        // Synthesize AgentDefinitions for inline specs so downstream executors can look them up
        for (spec in specs.filter { it.isInline }) {
            result[spec.id] = AgentDefinition.create(
                id = "inline-${spec.id}",
                name = spec.name.ifBlank { spec.id },
                description = spec.description,
                promptTemplate = spec.systemPrompt,
                toolNames = spec.toolNames,
                agentContext = AgentEnv.SWARM,
            )
        }

        // Log any missing agent definitions
        val missingIds = uniqueIds - result.keys
        for (id in missingIds) {
            logger.warn("AgentDefinition '{}' not found during batch load (userId={})", id, userId)
        }

        return result
    }

    /**
     * Pre-load all model configs for run-level caching.
     */
    suspend fun loadModelConfigCache(userId: String = "system"): List<ModelProviderConfig>? {
        return try {
            modelConfigStore?.getAllConfigs(userId)
        } catch (e: Exception) {
            logger.warn("Failed to pre-load model configs: {}", e.message)
            null
        }
    }

    /**
     * Convert an inline agent's [SwarmMcpBinding] into an [AgentToolConfig] so that
     * [com.easy.easyai.tools.mcp.McpToolProvider] can apply the same whitelist semantics
     * used for DB-bound agents. Empty [SwarmMcpBinding.toolNames] yields null metadata (= all tools).
     */
    private fun SwarmMcpBinding.toAgentToolConfig(agentId: String): AgentToolConfig {
        val metadata = toolNames.takeIf { it.isNotEmpty() }?.let { names ->
            ObjectMapper().writeValueAsString(mapOf("toolNames" to names))
        }
        return AgentToolConfig(
            id = "inline-${agentId}_mcp_$serverName",
            agentId = "inline-$agentId",
            targetType = TargetType.MCP,
            targetName = serverName,
            metadata = metadata,
        )
    }

    /**
     * Parse inputSchema JSON properties and map matching userVars values.
     * Returns empty map if inputSchema is null or parsing fails.
     */
    private fun parseInputVariables(
        inputSchema: String?,
        userVars: Map<String, String>
    ): Map<String, Any?> {
        if (inputSchema.isNullOrBlank()) return emptyMap()
        return try {
            val root = ObjectMapper().readTree(inputSchema)
            val properties = root.get("properties") ?: return emptyMap()
            val result = mutableMapOf<String, Any?>()
            properties.properties().forEach { (key, _) ->
                if (key in userVars) {
                    result[key] = userVars[key]
                }
            }
            result
        } catch (e: Exception) {
            logger.warn("Failed to parse inputSchema for input variables: {}", e.message)
            emptyMap()
        }
    }

    /**
     * Pre-render a Jinja2 template with flat variable names (userVars + inputFromVars).
     *
     * This is used for task-level templates (e.g. systemPromptTemplate) that reference
     * variables as `{{ varName }}` rather than `{{ input.varName }}`.
     */
    private fun preRenderTemplate(
        template: String,
        userVars: Map<String, String>,
        inputFromVars: Map<String, String>
    ): String {
        if (template.isBlank()) return template
        val model = mutableMapOf<String, Any>()
        userVars.forEach { (k, v) -> model[k] = v }
        inputFromVars.forEach { (k, v) -> model[k] = v }
        return try {
            templateRenderer.renderLiteralTemplate(template, model)
        } catch (e: Exception) {
            logger.warn("Failed to pre-render template: {}", e.message)
            template
        }
    }

    /**
     * Resolve model config from explicit ID, spec modelName, or null.
     */
    private suspend fun resolveModelConfig(
        spec: SwarmAgentSpec,
        modelConfigId: String?,
        modelConfigCache: List<ModelProviderConfig>?,
        userId: String,
    ): ModelProviderConfig? {
        val configs = modelConfigCache ?: try {
            modelConfigStore?.getAllConfigs(userId)
        } catch (e: Exception) {
            logger.warn("Failed to load model configs: {}", e.message)
            null
        } ?: return null

        // Priority 1: spec modelName
        if (spec.modelName != null) {
            return configs.find { it.modelId == spec.modelName }
        }

        // Priority 2: explicit workflow modelConfigId
        if (modelConfigId != null) {
            val found = configs.find { it.id == modelConfigId }
            if (found != null) return found
            logger.warn("Model config ID '{}' not found, falling back to modelName", modelConfigId)
        }

        return null
    }

    /**
     * Build a language instruction segment to append to the system prompt.
     * This ensures all LLM responses use the configured language regardless of prompt language.
     */
    private fun buildLanguageSegment(language: String): String =
        "\n\n---\n\n## Language\n" +
        "All responses MUST be written in $language. " +
        "Regardless of the language used in the system prompt, user messages, or conversation history, " +
        "always reply in $language."
}
