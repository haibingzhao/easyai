package com.easy.easyai.core.agent

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.ToolExecutionEngine
import com.easy.easyai.core.validation.OutputSchemaValidator
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.tool.ToolCallback

/**
 * Unified service interface encapsulating all Agent runtime infrastructure.
 * All implementations are Beans instantiated at Spring startup.
 *
 * An Agent depends on only two objects:
 * - [AgentContext]: unified context (identity + behavior config, e.g. model, tools)
 * - [AgentService]: system infrastructure (messageConverter, toolExecutor, hooks, etc.)
 */
interface AgentService {
    /**
     * ChatModel factory list supporting multiple protocols (e.g. OpenAI, Anthropic).
     * For internal use; prefer [supportsProtocol] / [createChatModel] externally.
     */
    val chatModelFactories: List<ChatModelFactory>

    /**
     * Message converter (EasyAI <-> Spring AI).
     */
    val messageConverter: MessageConverter

    /**
     * Tool execution engine.
     */
    val toolExecutor: ToolExecutionEngine

    /**
     * Message listener (for real-time persistence).
     * May be null (e.g. default Agent requires no persistence).
     */
    val messageListener: MessageListener?

    /**
     * Pre-tool-call hook.
     * Defaults to allowing all tool calls.
     */
    val beforeToolCall: BeforeToolCallHook

    /**
     * Post-tool-call hook.
     * Defaults to not interfering with execution results.
     */
    val afterToolCall: AfterToolCallHook

    /**
     * Context transformation service.
     * Used to transform message history before each LLM call (e.g. context compaction).
     */
    val transformContextService: TransformContextService

    /**
     * Default ChatModel instance used as a fallback.
     * Used when AgentContext.modelConfig is null or the protocol is unsupported.
     */
    val defaultChatModel: ChatModel

    /**
     * Lazily creates a ChatModel.
     * Called only when a specific model/config is needed, avoiding pre-creating all instances.
     *
     * @param config model provider configuration
     * @param toolCallbacks tool callback list
     * @param additionalOptions extra options (temperature, maxTokens, etc.)
     * @return ChatModel instance
     */
    fun createChatModel(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback> = emptyList(),
        additionalOptions: Map<String, Any?> = emptyMap()
    ): ChatModel

    /**
     * Builds ChatOptions.
     *
     * @param config model provider configuration
     * @param toolCallbacks tool callback list
     * @param additionalOptions extra options
     * @return ChatOptions instance
     */
    fun buildChatOptions(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback> = emptyList(),
        additionalOptions: Map<String, Any?> = emptyMap()
    ): ChatOptions

    /**
     * Checks whether the specified protocol is supported.
     * Iterates all registered ChatModelFactories; returns true if any supports it.
     */
    fun supportsProtocol(protocol: Protocol): Boolean = chatModelFactories.any { it.supports(protocol) }

    /**
     * Returns the creation timestamp mapping for messages in the current session.
     * Returns messageId -> createdAt (epoch millis), used during context compaction
     * to determine the correct ordering position for summary messages.
     *
     * Defaults to an empty map (e.g. in-memory sessions have no timestamp info).
     */
    suspend fun getMessageTimestamps(): Map<String, Long> = emptyMap()

    /**
     * Prompt template service for building system prompts at usage time.
     * Supports Jinja2 templates from AgentDefinition.promptTemplate,
     * with fallback to default SystemPromptBuilder.
     */
    val promptTemplateService: PromptTemplateService

    /**
     * Global event listeners (e.g., observability tracing, metrics).
     * These are invoked by [AgentRunner] on every event, in addition to
     * session-level listeners from [ChatSession.subscribe].
     *
     * Default: empty list.
     */
    val eventListeners: List<AgentEventListener> get() = emptyList()

    /**
     * Completion checks invoked when the agent loop is about to stop.
     * If any check returns [CompletionCheckResult.Continue], the loop resumes
     * with an optional prompt injected as UserMessage.
     *
     * Default: empty list (no completion checks).
     */
    val completionChecks: List<AgentCompletionCheck> get() = emptyList()

    /**
     * Memory store for persistent cross-session knowledge.
     * Default: null (memory system not configured).
     */
    val memoryStore: MemoryStore? get() = null

    /**
     * Callback invoked when the agent loop pauses waiting for user input
     * (permission request or ask_question).
     * Default: null (no pause tracking).
     */
    val waitForUserListener: WaitForUserListener? get() = null

    /**
     * Validator for structured output schema validation.
     * Used by OutputSchemaCompletionCheck as a fallback when
     * StructuredOutputChatOptions is not supported by the model.
     * Default: null (no output schema validation).
     */
    val outputSchemaValidator: OutputSchemaValidator? get() = null
}
