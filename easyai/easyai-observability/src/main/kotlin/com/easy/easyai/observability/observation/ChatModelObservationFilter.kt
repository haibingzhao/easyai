package com.easy.easyai.observability.observation

import com.easy.easyai.api.llm.observation.EasyAiChatModelObservationContext
import io.micrometer.common.KeyValue
import io.micrometer.observation.Observation
import io.micrometer.observation.ObservationFilter
import org.slf4j.LoggerFactory

/**
 * Observation filter that enriches chat-model observations with OpenTelemetry GenAI semantic conventions.
 *
 * This filter intercepts easyai [EasyAiChatModelObservationContext] observations (emitted by
 * [com.easy.easyai.api.llm.observation.ObservationChatModel]) and extracts model info, token usage,
 * prompts and completions, adding them as key values for tracing.
 *
 * OpenTelemetry GenAI semantic convention attributes added:
 * - `gen_ai.operation.name` - Always "chat" for chat model operations
 * - `gen_ai.request.model` - The model name from the request
 * - `gen_ai.response.model` - The model name from the response
 * - `gen_ai.request.temperature` - Temperature setting if available
 * - `gen_ai.request.max_tokens` - Max tokens setting if available
 * - `gen_ai.usage.input_tokens` - Number of tokens in the prompt
 * - `gen_ai.usage.output_tokens` - Number of tokens in the response
 * - `gen_ai.prompt` - The user prompt sent to the LLM
 * - `gen_ai.completion` - The LLM response/completion
 * - `input.value` - Same as prompt
 * - `output.value` - Same as completion
 *
 * @param maxAttributeLength maximum length for prompt/completion values before truncation
 *
 * @see [OpenTelemetry GenAI Spans](https://opentelemetry.io/docs/specs/semconv/gen-ai/gen-ai-spans/)
 */
class ChatModelObservationFilter(
    private val maxAttributeLength: Int = 4000
) : ObservationFilter {

    private val log = LoggerFactory.getLogger(ChatModelObservationFilter::class.java)

    /**
     * Enriches an [EasyAiChatModelObservationContext] with GenAI semantic convention key-values.
     *
     * Adds low-cardinality keys for model names and operation type, and high-cardinality
     * keys for hyperparameters, token usage, prompt content and completion content.
     * Non-chat contexts are returned unchanged.
     *
     * @param context the observation context to enrich
     * @return the enriched context (same instance)
     */
    override fun map(context: Observation.Context): Observation.Context {
        if (context !is EasyAiChatModelObservationContext) {
            return context
        }

        try {
            // OpenTelemetry GenAI semantic conventions
            context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.operation.name", "chat"))

            // Extract model info from request
            val request = context.request
            val options = request.options
            if (options != null) {
                val model = options.model
                if (!model.isNullOrEmpty()) {
                    context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.request.model", model))
                }
                options.temperature?.let { temp ->
                    context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.request.temperature", temp.toString()))
                }
                options.maxTokens?.let { maxTokens ->
                    context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.request.max_tokens", maxTokens.toString()))
                }
            }

            // Extract response model and token usage
            val response = context.response
            if (response != null) {
                val responseModel = response.metadata.model
                if (responseModel.isNotEmpty()) {
                    context.addLowCardinalityKeyValue(KeyValue.of("gen_ai.response.model", responseModel))
                }
                val usage = response.metadata.usage
                context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.input_tokens", usage.promptTokens.toString()))
                context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.usage.output_tokens", usage.completionTokens.toString()))
            }

            // Extract prompt from request
            val prompt = extractPrompt(context)
            if (!prompt.isNullOrEmpty()) {
                val truncated = truncate(prompt)
                context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.prompt", truncated))
                context.addHighCardinalityKeyValue(KeyValue.of("input.value", truncated))
            }

            // Extract completion from response
            val completion = extractCompletion(context)
            if (!completion.isNullOrEmpty()) {
                val truncated = truncate(completion)
                context.addHighCardinalityKeyValue(KeyValue.of("gen_ai.completion", truncated))
                context.addHighCardinalityKeyValue(KeyValue.of("output.value", truncated))
            }

        } catch (e: Exception) {
            log.debug("Failed to extract prompt/completion from EasyAiChatModelObservationContext", e)
        }

        return context
    }

    /**
     * Extracts the prompt from the chat request instructions.
     * Formats each message as `[MessageType]: text`, joined by newlines.
     *
     * @return the formatted prompt string, or null if no instructions are available
     */
    private fun extractPrompt(chatContext: EasyAiChatModelObservationContext): String? {
        val instructions = chatContext.request.instructions
        if (instructions.isEmpty()) {
            return null
        }

        return buildString {
            instructions.forEachIndexed { index, message ->
                if (index > 0) append("\n")
                append("[${message.messageType}]: ${message.text}")
            }
        }
    }

    /**
     * Extracts the LLM completion text from the chat response.
     *
     * @return the completion text, or null if the response or its output is unavailable
     */
    private fun extractCompletion(chatContext: EasyAiChatModelObservationContext): String? {
        val result = chatContext.response?.result ?: return null
        return result.output.text
    }

    /**
     * Truncates a value to [maxAttributeLength], appending "..." if truncated.
     *
     * @return the truncated string, or empty string if null
     */
    private fun truncate(value: String?): String {
        if (value == null) return ""
        return if (value.length > maxAttributeLength) {
            value.substring(0, maxAttributeLength) + "..."
        } else {
            value
        }
    }
}
