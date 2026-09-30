package com.easy.easyai.autoconfigure.dashscope

import org.springframework.ai.model.tool.DefaultToolCallingChatOptions
import org.springframework.ai.model.tool.StructuredOutputChatOptions
import org.springframework.ai.tool.ToolCallback

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
 * Spring AI 2.0.x ships no DashScope options type, so this carries the portable options
 * (model, sampling, tools, structured output) plus the Bailian-only knobs the ReAct loop needs:
 * thinking control, incremental streaming, and request-scoped credentials that the SDK
 * would otherwise read from process-wide globals.
 */
internal class DashScopeChatOptions(
    toolCallbacks: List<ToolCallback>,
    toolContext: Map<String, Any>,
    model: String?,
    frequencyPenalty: Double?,
    maxTokens: Int?,
    presencePenalty: Double?,
    stopSequences: List<String>?,
    temperature: Double?,
    topK: Int?,
    topP: Double?,
    private val outputSchemaValue: String?,
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
) : DefaultToolCallingChatOptions(
    toolCallbacks,
    toolContext,
    model,
    frequencyPenalty,
    maxTokens,
    presencePenalty,
    stopSequences,
    temperature,
    topK,
    topP
),
    StructuredOutputChatOptions {

    override fun getOutputSchema(): String? = outputSchemaValue

    override fun mutate(): Builder = Builder(this)

    override fun equals(other: Any?): Boolean =
        this === other || (other is DashScopeChatOptions && super.equals(other) &&
            outputSchemaValue == other.outputSchemaValue &&
            apiKey == other.apiKey &&
            enableThinking == other.enableThinking &&
            thinkingBudget == other.thinkingBudget &&
            reasoningEffort == other.reasoningEffort &&
            resultFormat == other.resultFormat &&
            responseFormatKind == other.responseFormatKind &&
            enableSearch == other.enableSearch &&
            seed == other.seed &&
            repetitionPenalty == other.repetitionPenalty &&
            parallelToolCalls == other.parallelToolCalls &&
            extraParameters == other.extraParameters)

    @Suppress("MagicNumber")
    override fun hashCode(): Int {
        var result = super.hashCode()
        result = 31 * result + (outputSchemaValue?.hashCode() ?: 0)
        result = 31 * result + (apiKey?.hashCode() ?: 0)
        result = 31 * result + (enableThinking?.hashCode() ?: 0)
        result = 31 * result + (thinkingBudget ?: 0)
        result = 31 * result + (reasoningEffort?.hashCode() ?: 0)
        result = 31 * result + (resultFormat?.hashCode() ?: 0)
        result = 31 * result + responseFormatKind.hashCode()
        result = 31 * result + (enableSearch?.hashCode() ?: 0)
        result = 31 * result + (seed ?: 0)
        result = 31 * result + (repetitionPenalty?.hashCode() ?: 0)
        result = 31 * result + (parallelToolCalls?.hashCode() ?: 0)
        result = 31 * result + extraParameters.hashCode()
        return result
    }

    internal class Builder :
        DefaultToolCallingChatOptions.Builder<Builder>,
        StructuredOutputChatOptions.Builder<Builder> {

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

        constructor() : super()

        constructor(options: DashScopeChatOptions) : super() {
            model(options.model)
            temperature(options.temperature)
            maxTokens(options.maxTokens)
            topP(options.topP)
            topK(options.topK)
            presencePenalty(options.presencePenalty)
            frequencyPenalty(options.frequencyPenalty)
            stopSequences(options.stopSequences)
            toolCallbacks(options.toolCallbacks)
            toolContext(options.toolContext)
            outputSchemaValue = options.outputSchemaValue
            apiKey = options.apiKey
            enableThinking = options.enableThinking
            thinkingBudget = options.thinkingBudget
            reasoningEffort = options.reasoningEffort
            resultFormat = options.resultFormat
            responseFormatKind = options.responseFormatKind
            enableSearch = options.enableSearch
            seed = options.seed
            repetitionPenalty = options.repetitionPenalty
            parallelToolCalls = options.parallelToolCalls
            extraParameters = options.extraParameters
        }

        override fun outputSchema(outputSchema: String?): Builder = apply { outputSchemaValue = outputSchema }

        fun apiKey(apiKey: String?): Builder = apply { this.apiKey = apiKey }


        fun enableThinking(enableThinking: Boolean?): Builder = apply { this.enableThinking = enableThinking }

        fun thinkingBudget(thinkingBudget: Int?): Builder = apply { this.thinkingBudget = thinkingBudget }

        fun reasoningEffort(reasoningEffort: String?): Builder = apply { this.reasoningEffort = reasoningEffort }


        fun resultFormat(resultFormat: String?): Builder = apply { this.resultFormat = resultFormat }

        fun responseFormatKind(kind: DashScopeResponseFormatKind): Builder = apply { responseFormatKind = kind }

        fun enableSearch(enableSearch: Boolean?): Builder = apply { this.enableSearch = enableSearch }

        fun seed(seed: Int?): Builder = apply { this.seed = seed }

        fun repetitionPenalty(repetitionPenalty: Float?): Builder = apply { this.repetitionPenalty = repetitionPenalty }

        fun parallelToolCalls(parallelToolCalls: Boolean?): Builder = apply { this.parallelToolCalls = parallelToolCalls }

        fun extraParameters(extraParameters: Map<String, Any?>): Builder = apply { this.extraParameters = extraParameters }

        override fun build(): DashScopeChatOptions = DashScopeChatOptions(
            toolCallbacks = toolCallbacks.orEmpty(),
            toolContext = toolContext.orEmpty(),
            model = model,
            frequencyPenalty = frequencyPenalty,
            maxTokens = maxTokens,
            presencePenalty = presencePenalty,
            stopSequences = stopSequences,
            temperature = temperature,
            topK = topK,
            topP = topP,
            outputSchemaValue = outputSchemaValue,
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

        internal fun builderFrom(options: DashScopeChatOptions): Builder = Builder(options)
    }
}
