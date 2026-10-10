package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.client.okhttp.AnthropicOkHttpClientAsync
import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.llm.observation.ObservationChatModel
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Anthropic implementation of ChatModelFactory. Builds sync/async clients on the official
 * `com.anthropic` SDK (with the raw-error logging interceptor) and returns [AnthropicStreamingChatModel]
 * wrapped for observation, and builds per-turn [AnthropicChatOptions] from the provider configuration.
 */
class AnthropicChatModelFactory : ChatModelFactory {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(protocol: Protocol): Boolean = protocol == Protocol.ANTHROPIC

    override fun create(config: ModelProviderConfig, observationRegistry: ObservationRegistry): ChatModel {
        val apiKey = config.apiKey
            ?: throw IllegalStateException("API key is required for Anthropic provider")

        val baseUrl = config.baseUrl ?: "https://api.anthropic.com"
        val timeout = Duration.ofSeconds(config.timeoutSeconds)

        // Log raw non-2xx bodies so gateway errors returned inside SSE frames — which the
        // Anthropic SDK otherwise reduces to "400: Unknown" with body=JsonMissing — remain
        // diagnosable. Applied to both sync and async clients via the SDK interceptor seam.
        val errorInterceptor = AnthropicErrorLoggingInterceptor()

        val syncClient = AnthropicOkHttpClient.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .timeout(timeout)
            .maxRetries(2)
            .addInterceptor(errorInterceptor)
            .build()

        val asyncClient = AnthropicOkHttpClientAsync.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .timeout(timeout)
            .maxRetries(2)
            .addInterceptor(errorInterceptor)
            .build()

        // timeoutSeconds travels into per-request RequestOptions; otherwise the mapper's
        // DEFAULT_TIMEOUT_SECONDS floor would silently override the configured client timeout.
        val defaultOptions = AnthropicChatOptions(model = config.modelId, timeoutSeconds = config.timeoutSeconds)
        val mapper = AnthropicStreamingChatModel(syncClient, asyncClient, defaultOptions)
        return ObservationChatModel(mapper, observationRegistry)
    }

    override fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>,
        outputSchema: String?
    ): ChatOptions {
        var options = AnthropicChatOptions(
            model = config.modelId,
            toolCallbacks = toolCallbacks,
            timeoutSeconds = config.timeoutSeconds
        )

        config.options?.let {
            if (it.thinking) {
                // Thinking: pass thinking.budget_tokens when enabled. The mapper floors
                // max_tokens to budget + reserve, so no explicit bump is needed here.
                options = options.copy(
                    thinkingBudget = DEFAULT_THINKING_BUDGET_TOKENS,
                    maxTokens = maxOf(it.maxTokens, DEFAULT_THINKING_BUDGET_TOKENS.toInt() + 1)
                )
            } else {
                // Effort maps to reasoning_effort. Some Anthropic-compatible gateways (e.g.
                // Bailian token-plan) reject reasoning_effort alongside thinking.budget_tokens, so
                // it is only emitted when thinking is off; the mapper sends an explicit
                // {type: disabled} thinking config whenever thinkingBudget is null (some models
                // reason by DEFAULT, e.g. qwen3.x-max, so merely omitting the field leaves it on).
                options = options.copy(
                    effort = it.effort,
                    temperature = it.temperature,
                    maxTokens = it.maxTokens
                )
            }
        }

        // Structured output: the Anthropic mapper does not put the schema on the wire (spring-ai
        // 2.0.1 never did either — its AnthropicChatModel ignores StructuredOutputChatOptions).
        // options.outputSchema is recorded for the loop's bookkeeping; actual enforcement is the
        // prompt-based OutputSchemaCompletionCheck path in easyai-core.
        if (outputSchema != null) {
            when (config.capabilities?.structuredOutput) {
                // null = undeclared: record the schema; a future wire-level output_config hookup
                // (SDK 2.52 OutputConfig.format) would start enforcing at the API here.
                null, StructuredOutputSupport.JSON_SCHEMA -> options = options.copy(outputSchema = outputSchema)
                StructuredOutputSupport.JSON_OBJECT -> logger.debug(
                    "Model {} declares JSON_OBJECT-only structured output, which the Anthropic protocol cannot express; using prompt-based enforcement",
                    config.modelId
                )
                StructuredOutputSupport.NONE -> Unit
            }
        }
        return options
    }

    companion object {
        /** Default token budget for extended thinking when enabled. */
        private const val DEFAULT_THINKING_BUDGET_TOKENS = 10_000L
    }
}
