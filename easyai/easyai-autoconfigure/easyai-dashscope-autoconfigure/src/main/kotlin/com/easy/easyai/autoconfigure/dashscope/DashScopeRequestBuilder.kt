package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.GenerationParam
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam
import com.alibaba.dashscope.common.Message
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.common.ResponseFormat
import com.alibaba.dashscope.tools.FunctionDefinition
import com.alibaba.dashscope.tools.ToolCallBase
import com.alibaba.dashscope.tools.ToolCallFunction
import com.alibaba.dashscope.tools.ToolFunction
import com.alibaba.dashscope.utils.JsonUtils
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.easy.easyai.api.llm.ToolCallback
import org.slf4j.LoggerFactory

/**
 * Renders [DashScopeMessageSpec]s and [DashScopeChatOptions] into DashScope SDK request params.
 *
 * Bailian sends several useful knobs that the SDK exposes no first-class setter for
 * (`reasoning_effort`, `max_completion_tokens`), so those travel through the
 * `parameters` escape hatch rather than being dropped.
 */
internal object DashScopeRequestBuilder {

    private val logger = LoggerFactory.getLogger(DashScopeRequestBuilder::class.java)

    /** Only hybrid-reasoning models accept `enable_thinking`; others reject the key outright. */
    private const val RESULT_FORMAT_MESSAGE = "message"

    fun textParam(
        specs: List<DashScopeMessageSpec>,
        tools: List<ToolCallback>,
        options: DashScopeChatOptions,
        streaming: Boolean
    ): GenerationParam {
        val builder = GenerationParam.builder()
        builder.model(requireNotNull(options.model) { "DashScope model id is required" })
        builder.apiKey(options.apiKey)
        builder.messages(specs.map { it.toTextMessage() })
        builder.resultFormat(options.resultFormat ?: RESULT_FORMAT_MESSAGE)
        options.temperature?.let { builder.temperature(it.toFloat()) }
        options.maxTokens?.let { builder.maxTokens(it) }
        options.topP?.let { builder.topP(it) }
        options.topK?.let { builder.topK(it) }
        options.seed?.let { builder.seed(it) }
        options.repetitionPenalty?.let { builder.repetitionPenalty(it) }
        options.stopSequences?.takeIf { it.isNotEmpty() }?.let { builder.stopStrings(it) }
        options.enableSearch?.let { builder.enableSearch(it) }
        options.parallelToolCalls?.let { builder.parallelToolCalls(it) }
        if (streaming) builder.incrementalOutput(true)

        val toolBases = tools.map { it.toToolFunction() }
        if (toolBases.isNotEmpty()) builder.tools(toolBases)

        applyThinking(builder, options)
        applyStructuredOutput(toolBases.isNotEmpty(), options)?.let { builder.responseFormat(it) }
        applyExtraParameters(builder, options)
        return builder.build()
    }

    fun multiModalParam(
        specs: List<DashScopeMessageSpec>,
        tools: List<ToolCallback>,
        options: DashScopeChatOptions,
        streaming: Boolean
    ): MultiModalConversationParam {
        val builder = MultiModalConversationParam.builder()
        builder.model(requireNotNull(options.model) { "DashScope model id is required" })
        builder.apiKey(options.apiKey)
        builder.messages(specs.map { it.toMultiModalMessage() })
        options.temperature?.let { builder.temperature(it.toFloat()) }
        options.maxTokens?.let { builder.maxTokens(it) }
        options.topP?.let { builder.topP(it) }
        options.topK?.let { builder.topK(it) }
        options.seed?.let { builder.seed(it) }
        options.enableThinking?.let { builder.enableThinking(it) }
        options.thinkingBudget?.let { builder.thinkingBudget(it) }
        if (streaming) builder.incrementalOutput(true)

        val toolBases = tools.map { it.toToolFunction() }
        if (toolBases.isNotEmpty()) builder.tools(toolBases)

        applyExtraParametersToMultiModal(builder, options)
        return builder.build()
    }

    // region spec -> SDK message

    private fun DashScopeMessageSpec.toTextMessage(): Message {
        val builder = Message.builder()
        builder.role(role)
        builder.content(text ?: "")
        toolCallId?.let { builder.toolCallId(it) }
        toolName?.let { builder.name(it) }
        if (toolCalls.isNotEmpty()) builder.toolCalls(toolCalls.map { it.toToolCallBase() })
        return builder.build()
    }

    private fun DashScopeMessageSpec.toMultiModalMessage(): MultiModalMessage {
        val content = mutableListOf<Map<String, Any>>()
        images.forEach { content.add(mapOf("image" to it)) }
        if (content.isEmpty() || !text.isNullOrEmpty()) content.add(mapOf("text" to (text ?: "")))

        val builder = MultiModalMessage.builder()
        builder.role(role)
        builder.content(content)
        toolCallId?.let { builder.toolCallId(it) }
        toolName?.let { builder.name(it) }
        if (toolCalls.isNotEmpty()) builder.toolCalls(toolCalls.map { it.toToolCallBase() })
        return builder.build()
    }

    /**
     * `ToolCallFunction.CallFunction` is a non-static Java inner class and Kotlin cannot call a
     * non-static inner constructor, so the object is materialized through the SDK's own Gson.
     */
    private fun DashScopeToolCallSpec.toToolCallBase(): ToolCallBase {
        val function = JsonObject().apply {
            addProperty("name", name)
            addProperty("arguments", arguments)
        }
        val call = JsonObject().apply {
            addProperty("id", id)
            addProperty("type", "function")
            add("function", function)
        }
        return JsonUtils.fromJsonObject(call, ToolCallFunction::class.java)
    }

    // endregion

    // region tools

    private fun ToolCallback.toToolFunction(): ToolFunction = ToolFunction.builder()
        .function(
            FunctionDefinition.builder()
                .name(name)
                .description(description)
                .parameters(inputSchema.toJsonParameters())
                .build()
        )
        .build()

    /** Bailian rejects a malformed schema, so an unusable one degrades to "any object". */
    internal fun String.toJsonParameters(): JsonObject = runCatching {
        JsonParser.parseString(this).asJsonObject
    }.getOrElse {
        logger.warn("Tool input schema is not a JSON object, sending an empty schema instead: {}", it.message)
        JsonObject()
    }

    // endregion

    // region parameters

    private fun applyThinking(builder: GenerationParam.GenerationParamBuilder<*, *>, options: DashScopeChatOptions) {
        options.enableThinking?.let { builder.enableThinking(it) }
        options.thinkingBudget?.let { builder.thinkingBudget(it) }
    }

    private fun applyExtraParameters(
        builder: GenerationParam.GenerationParamBuilder<*, *>,
        options: DashScopeChatOptions
    ) {
        effectiveExtraParameters(options).forEach { (key, value) ->
            if (value != null) builder.parameter(key, value)
        }
    }

    private fun applyExtraParametersToMultiModal(
        builder: MultiModalConversationParam.MultiModalConversationParamBuilder<*, *>,
        options: DashScopeChatOptions
    ) {
        effectiveExtraParameters(options).forEach { (key, value) ->
            if (value != null) builder.parameter(key, value)
        }
    }

    private fun effectiveExtraParameters(options: DashScopeChatOptions): Map<String, Any?> {
        val merged = options.extraParameters.toMutableMap()
        // `reasoning_effort` has no SDK setter; effort is already mutually exclusive with
        // thinkingBudget, which the factory enforces when it builds the options.
        options.reasoningEffort?.let { merged["reasoning_effort"] = it }
        return merged
    }

    /**
     * Bailian rejects `response_format` alongside `tools`, so tool calling wins and the schema is
     * conveyed by the prompt instead (OutputSchemaCompletionCheck keeps the fallback enforcement).
     */
    private fun applyStructuredOutput(
        hasTools: Boolean,
        options: DashScopeChatOptions
    ): ResponseFormat? {
        val schema = options.outputSchema ?: return null
        if (hasTools) {
            logger.debug("Skipping API-level structured output because tools are registered for model {}", options.model)
            return null
        }
        return when (options.responseFormatKind) {
            DashScopeResponseFormatKind.JSON_OBJECT -> ResponseFormat.builder()
                .type(ResponseFormat.JSON_OBJECT)
                .build()
            DashScopeResponseFormatKind.JSON_SCHEMA -> ResponseFormat.builder()
                .type(ResponseFormat.JSON_SCHEMA)
                .jsonSchema(
                    ResponseFormat.JsonSchemaFormat.builder()
                        .name(STRUCTURED_OUTPUT_NAME)
                        .schema(schema.toJsonParameters())
                        .strict(true)
                        .build()
                )
                .build()
            DashScopeResponseFormatKind.NONE -> null
        }
    }

    private const val STRUCTURED_OUTPUT_NAME = "response"
}
