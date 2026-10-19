package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.llm.observation.ObservationChatModel
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.SamplingPolicy
import com.easy.easyai.api.model.StructuredOutputPolicy
import com.easy.easyai.api.model.StructuredOutputSupport
import com.openai.client.OpenAIClientAsyncImpl
import com.openai.client.okhttp.OkHttpClient
import com.openai.core.ClientOptions
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * OpenAI implementation of ChatModelFactory. Builds a client on the official `com.openai` SDK and
 * returns [OpenAiStreamingChatModel] (wrapped for observation), and builds per-turn
 * [OpenAiChatOptions] from the provider configuration.
 */
class OpenAiChatModelFactory : ChatModelFactory {
    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(protocol: Protocol): Boolean = protocol == Protocol.OPENAI

    override fun create(config: ModelProviderConfig, observationRegistry: ObservationRegistry): ChatModel {
        val apiKey = config.apiKey
            ?: throw IllegalStateException("API key is required for OpenAI provider")

        val baseUrl = config.baseUrl ?: "https://api.openai.com"
        val timeout = Duration.ofSeconds(config.timeoutSeconds)

        // `OpenAIOkHttpClientAsync.Builder.build()` installs its own HTTP client unconditionally, so the
        // only way to put the error-body logger on the transport is to assemble ClientOptions here —
        // with the same settings that builder would have produced (request timeout, no SDK retries).
        val asyncClient = OpenAIClientAsyncImpl(
            ClientOptions.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .timeout(timeout)
                // No SDK-level retries. On a retryable status the SDK sleeps for whatever
                // Retry-After demands on its own java.util.Timer, which sits outside the call
                // timeout above — a spent quota (429) can stall a turn for as long as the
                // provider says. The agent loop owns retries instead: it classifies the failure,
                // surfaces a RetryEvent, and refuses to retry what can never succeed.
                .maxRetries(0)
                .httpClient(
                    // Gateways that answer with a body the SDK cannot parse collapse to
                    // "400: Unknown"; peek the raw body so the real reason stays in the log.
                    OpenAiErrorLoggingHttpClient(
                        OkHttpClient.builder().timeout(timeout).build()
                    )
                )
                .build()
        )

        // timeoutSeconds travels into per-request RequestOptions; otherwise the mapper's
        // DEFAULT_TIMEOUT_SECONDS floor would silently override the configured client timeout.
        val defaultOptions = OpenAiChatOptions(model = config.modelId, timeoutSeconds = config.timeoutSeconds)
        val mapper = OpenAiStreamingChatModel(asyncClient, defaultOptions)
        return ObservationChatModel(mapper, observationRegistry)
    }

    override fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>,
        outputSchema: String?
    ): ChatOptions {
        var options = OpenAiChatOptions(
            model = config.modelId,
            toolCallbacks = toolCallbacks,
            timeoutSeconds = config.timeoutSeconds
        )

        config.options?.let {
            // Use effort if set, otherwise fall back to thinking -> "high"
            val effortValue = it.effort ?: if (it.thinking) "high" else null
            if (effortValue != null) {
                // OpenAI reasoning models (o-series) only support low/medium/high
                val mapped = when (effortValue.lowercase()) {
                    "low" -> "low"
                    "medium" -> "medium"
                    else -> "high"  // high/xhigh/max all map to "high"
                }
                options = options.copy(reasoningEffort = mapped, maxCompletionTokens = it.maxTokens)
            } else {
                // Gated, not read straight off the row: a model that declares sampling NOT_SETTABLE
                // must never see the row number, whichever caller built these options.
                options = options.copy(
                    temperature = SamplingPolicy.rowTemperature(config),
                    maxTokens = it.maxTokens
                )
            }
        }

        if (outputSchema != null) {
            // Tools win over the response format, exactly as the native DashScope builder already does:
            // a Bailian-family gateway answers 400 when both ride on one request, and a 400 kills the
            // whole turn whereas a missing format only costs API-level enforcement — the schema is still
            // rendered into the system prompt and the completion check validates the answer. OpenAI's
            // own API does accept both, so a row pointed at it loses enforcement it could have had; the
            // cheap way back is dropping tool schemas for that turn, which is what a repair turn does.
            if (toolCallbacks.isNotEmpty()) {
                logger.debug(
                    "{} tool schemas registered for model {}; skipping API-level response format",
                    toolCallbacks.size, config.modelId
                )
            } else {
                when (StructuredOutputPolicy.of(config)) {
                    StructuredOutputSupport.JSON_SCHEMA -> options = options.copy(
                        responseFormatKind = OpenAiResponseFormatKind.JSON_SCHEMA,
                        outputSchema = outputSchema
                    )
                    // Schema-less JSON guarantee; the schema itself is conveyed via the prompt.
                    StructuredOutputSupport.JSON_OBJECT -> options = options.copy(
                        responseFormatKind = OpenAiResponseFormatKind.JSON_OBJECT
                    )
                    // No API-level enforcement; OutputSchemaCompletionCheck nudges with the schema.
                    // This is also what an undeclared row gets — see [StructuredOutputPolicy].
                    StructuredOutputSupport.NONE -> logger.debug(
                        "Model {} declares no API-level structured output; using prompt-based enforcement",
                        config.modelId
                    )
                }
            }
        }
        return options
    }
}
