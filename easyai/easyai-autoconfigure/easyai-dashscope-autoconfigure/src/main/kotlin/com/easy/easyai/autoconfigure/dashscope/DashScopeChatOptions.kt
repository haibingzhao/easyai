package com.easy.easyai.autoconfigure.dashscope

import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ToolCallback

/**
 * How `response_format` should be expressed for a turn, derived by the factory from the model's
 * declared `capabilities.structuredOutput` (undeclared means full support).
 */
internal enum class DashScopeResponseFormatKind {
    JSON_SCHEMA,
    JSON_OBJECT,
    NONE
}

/**
 * Chat options for the DashScope (Aliyun Bailian) native protocol.
 *
 * Implements the portable [ChatOptions] fields (model, sampling, tools, structured output) plus the
 * Bailian-only knobs the ReAct loop needs: thinking control, incremental streaming, and
 * request-scoped credentials that the SDK would otherwise read from process-wide globals.
 */
internal data class DashScopeChatOptions(
    override val model: String?,
    override val temperature: Double?,
    override val maxTokens: Int?,
    val topP: Double?,
    val topK: Int?,
    val frequencyPenalty: Double?,
    val presencePenalty: Double?,
    val stopSequences: List<String>?,
    val toolContext: Map<String, Any>,
    override val toolCallbacks: List<ToolCallback>,
    override val outputSchema: String?,
    val apiKey: String?,
    val enableThinking: Boolean?,
    val thinkingBudget: Int?,
    val reasoningEffort: String?,
    val resultFormat: String?,
    val responseFormatKind: DashScopeResponseFormatKind,
    val enableSearch: Boolean?,
    val seed: Int?,
    val repetitionPenalty: Float?,
    val parallelToolCalls: Boolean?,
    /** Bailian request parameters with no first-class SDK field (e.g. `max_completion_tokens`). */
    val extraParameters: Map<String, Any?>
) : ChatOptions {

    // Mask the credential: a data-class toString() would leak apiKey into any log line that
    // renders options (error paths included).
    override fun toString(): String =
        "DashScopeChatOptions(model=$model, responseFormatKind=$responseFormatKind, apiKey=${apiKey?.let { "***" }})"

    internal class Builder {
        private var model: String? = null
        private var temperature: Double? = null
        private var maxTokens: Int? = null
        private var topP: Double? = null
        private var topK: Int? = null
        private var frequencyPenalty: Double? = null
        private var presencePenalty: Double? = null
        private var stopSequences: List<String>? = null
        private var toolContext: Map<String, Any> = emptyMap()
        private var toolCallbacks: List<ToolCallback> = emptyList()
        private var outputSchemaValue: String? = null
        private var apiKey: String? = null
        private var enableThinking: Boolean? = null
        private var thinkingBudget: Int? = null
        private var reasoningEffort: String? = null
        private var resultFormat: String? = null
        private var responseFormatKind: DashScopeResponseFormatKind = DashScopeResponseFormatKind.JSON_SCHEMA
        private var enableSearch: Boolean? = null
        private var seed: Int? = null
        private var repetitionPenalty: Float? = null
        private var parallelToolCalls: Boolean? = null
        private var extraParameters: Map<String, Any?> = emptyMap()

        fun model(model: String?) = apply { this.model = model }
        fun temperature(temperature: Double?) = apply { this.temperature = temperature }
        fun maxTokens(maxTokens: Int?) = apply { this.maxTokens = maxTokens }
        fun topP(topP: Double?) = apply { this.topP = topP }
        fun topK(topK: Int?) = apply { this.topK = topK }
        fun frequencyPenalty(frequencyPenalty: Double?) = apply { this.frequencyPenalty = frequencyPenalty }
        fun presencePenalty(presencePenalty: Double?) = apply { this.presencePenalty = presencePenalty }
        fun stopSequences(stopSequences: List<String>?) = apply { this.stopSequences = stopSequences }
        fun toolContext(toolContext: Map<String, Any>) = apply { this.toolContext = toolContext }
        fun toolCallbacks(toolCallbacks: List<ToolCallback>) = apply { this.toolCallbacks = toolCallbacks }
        fun outputSchema(outputSchema: String?) = apply { outputSchemaValue = outputSchema }
        fun apiKey(apiKey: String?) = apply { this.apiKey = apiKey }
        fun enableThinking(enableThinking: Boolean?) = apply { this.enableThinking = enableThinking }
        fun thinkingBudget(thinkingBudget: Int?) = apply { this.thinkingBudget = thinkingBudget }
        fun reasoningEffort(reasoningEffort: String?) = apply { this.reasoningEffort = reasoningEffort }
        fun resultFormat(resultFormat: String?) = apply { this.resultFormat = resultFormat }
        fun responseFormatKind(kind: DashScopeResponseFormatKind) = apply { responseFormatKind = kind }
        fun enableSearch(enableSearch: Boolean?) = apply { this.enableSearch = enableSearch }
        fun seed(seed: Int?) = apply { this.seed = seed }
        fun repetitionPenalty(repetitionPenalty: Float?) = apply { this.repetitionPenalty = repetitionPenalty }
        fun parallelToolCalls(parallelToolCalls: Boolean?) = apply { this.parallelToolCalls = parallelToolCalls }
        fun extraParameters(extraParameters: Map<String, Any?>) = apply { this.extraParameters = extraParameters }

        fun build(): DashScopeChatOptions = DashScopeChatOptions(
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            topK = topK,
            frequencyPenalty = frequencyPenalty,
            presencePenalty = presencePenalty,
            stopSequences = stopSequences,
            toolContext = toolContext,
            toolCallbacks = toolCallbacks,
            outputSchema = outputSchemaValue,
            apiKey = apiKey,
            enableThinking = enableThinking,
            thinkingBudget = thinkingBudget,
            reasoningEffort = reasoningEffort,
            resultFormat = resultFormat,
            responseFormatKind = responseFormatKind,
            enableSearch = enableSearch,
            seed = seed,
            repetitionPenalty = repetitionPenalty,
            parallelToolCalls = parallelToolCalls,
            extraParameters = extraParameters
        )
    }

    companion object {
        fun builder(): Builder = Builder()

        internal fun builderFrom(options: DashScopeChatOptions): Builder = Builder()
            .model(options.model)
            .temperature(options.temperature)
            .maxTokens(options.maxTokens)
            .topP(options.topP)
            .topK(options.topK)
            .frequencyPenalty(options.frequencyPenalty)
            .presencePenalty(options.presencePenalty)
            .stopSequences(options.stopSequences)
            .toolContext(options.toolContext)
            .toolCallbacks(options.toolCallbacks)
            .outputSchema(options.outputSchema)
            .apiKey(options.apiKey)
            .enableThinking(options.enableThinking)
            .thinkingBudget(options.thinkingBudget)
            .reasoningEffort(options.reasoningEffort)
            .resultFormat(options.resultFormat)
            .responseFormatKind(options.responseFormatKind)
            .enableSearch(options.enableSearch)
            .seed(options.seed)
            .repetitionPenalty(options.repetitionPenalty)
            .parallelToolCalls(options.parallelToolCalls)
            .extraParameters(options.extraParameters)
    }
}
