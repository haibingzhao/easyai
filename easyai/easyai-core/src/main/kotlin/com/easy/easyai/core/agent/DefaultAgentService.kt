package com.easy.easyai.core.agent

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.ToolExecutionEngine
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.core.validation.OutputSchemaValidator
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.tool.ToolCallback

/**
 * Default implementation of [AgentService].
 *
 * All dependencies are injected by the Spring container at startup and managed as Beans.
 * Override default behavior by implementing [AgentService] and providing a custom Bean.
 *
 * Implements [BeforeToolCallHook] and [AfterToolCallHook] directly,
 * so the service itself serves as the hook implementation.
 */
class DefaultAgentService(
    override val chatModelFactories: List<ChatModelFactory>,
    override val messageConverter: MessageConverter,
    override val toolExecutor: ToolExecutionEngine,
    override val promptTemplateService: PromptTemplateService,
    override val defaultChatModel: ChatModel,
    override val messageListener: MessageListener? = null,
    private val permissionService: PermissionService? = null,
    private val toolFactory: ToolFactory? = null,
    override val transformContextService: TransformContextService = DefaultTransformContextService(),
    override val eventListeners: List<AgentEventListener> = emptyList(),
    override val completionChecks: List<AgentCompletionCheck> = emptyList(),
    /**
     * ObservationRegistry for Spring AI LLM call tracing.
     * When a real registry is provided, all ChatModel instances created via [createChatModel]
     * will emit GenAI observation spans (model name, token usage, prompt/completion, etc.).
     * Defaults to [ObservationRegistry.NOOP] to disable observation when not configured.
     */
    private val observationRegistry: ObservationRegistry = ObservationRegistry.NOOP,
    override val memoryStore: MemoryStore? = null,
    override val knowledgeStore: KnowledgeStore? = null,
    override val waitForUserListener: WaitForUserListener? = null,
    override val outputSchemaValidator: OutputSchemaValidator? = null
) : AgentService, BeforeToolCallHook, AfterToolCallHook {

    /**
     * Cached set of tool names that are restricted to the main agent.
     * Avoids scanning builders on every tool call.
     */
    @Volatile
    private var mainAgentOnlyTools: Set<String>? = null

    private fun isMainAgentOnly(toolName: String): Boolean {
        val cache = mainAgentOnlyTools
        if (cache != null) return toolName in cache
        val factory = toolFactory ?: return false
        val restricted = try {
            factory.getBuilders().filter { it.mainAgentOnly }.map { it.name }.toSet()
        } catch (_: Exception) {
            emptySet()
        }
        mainAgentOnlyTools = restricted
        return toolName in restricted
    }

    override suspend fun invoke(context: BeforeToolCallContext): BeforeToolCallResult {
        // Check tool-level restrictions (e.g., mainAgentOnly)
        if (context.parentAgentId != null && isMainAgentOnly(context.toolName)) {
            return BeforeToolCallResult.Block("Sub-agents cannot use ${context.toolName}")
        }

        if (permissionService != null && context.projectId != null) {
            val result = permissionService.evaluateForToolCall(
                toolCallId = context.toolCallId,
                toolName = context.toolName,
                arguments = context.arguments,
                projectId = context.projectId,
                projectPath = context.projectPath
            )
            return when (result.action) {
                PermissionAction.ALLOW -> BeforeToolCallResult.Allow
                PermissionAction.DENY -> BeforeToolCallResult.Block(
                    reason = "Permission denied for ${context.toolName}: ${result.pattern}"
                )
                PermissionAction.ASK -> {
                    if (context.parentAgentId != null) {
                        // Sub-agents cannot request user permissions — block the tool call
                        BeforeToolCallResult.Block(
                            "Sub-agent cannot request permission for ${context.toolName} (${result.permission}:${result.pattern}). " +
                            "Use 'Always Allow' in settings to pre-authorize this tool, or adjust the sub-agent's tool permissions."
                        )
                    } else {
                        BeforeToolCallResult.PermissionRequest(
                            permission = result.permission,
                            pattern = result.pattern,
                            toolCallId = context.toolCallId,
                            toolName = context.toolName,
                            arguments = context.arguments
                        )
                    }
                }
            }
        }

        return BeforeToolCallResult.Allow
    }

    override suspend fun invoke(context: AfterToolCallContext): AfterToolCallResult {
        return AfterToolCallResult.Default
    }

    // AgentService returns itself as the hook implementation
    override val beforeToolCall: BeforeToolCallHook get() = this
    override val afterToolCall: AfterToolCallHook get() = this

    private fun findFactory(protocol: Protocol): ChatModelFactory {
        return chatModelFactories.firstOrNull { it.supports(protocol) }
            ?: throw IllegalStateException("No ChatModelFactory supports protocol: $protocol")
    }

    override fun createChatModel(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>
    ): ChatModel {
        return findFactory(config.protocol).create(config, observationRegistry)
    }

    override fun buildChatOptions(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>
    ): ChatOptions {
        return findFactory(config.protocol).build(config, toolCallbacks)
    }

}
