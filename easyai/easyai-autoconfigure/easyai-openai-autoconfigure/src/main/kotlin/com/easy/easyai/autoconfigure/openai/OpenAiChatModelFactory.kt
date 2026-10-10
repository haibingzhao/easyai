package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.llm.ChatOptions
import com.easy.easyai.api.llm.ToolCallback
import com.easy.easyai.api.llm.observation.ObservationChatModel
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import com.openai.client.okhttp.OpenAIOkHttpClientAsync
import io.micrometer.observation.ObservationRegistry
import java.time.Duration

/**
 * OpenAI implementation of ChatModelFactory. Builds a client on the official `com.openai` SDK and
 * returns [OpenAiStreamingChatModel] (wrapped for observation), and builds per-turn
 * [OpenAiChatOptions] from the provider configuration.
 */
class OpenAiChatModelFactory : ChatModelFactory {
    override fun supports(protocol: Protocol): Boolean = protocol == Protocol.OPENAI

    override fun create(config: ModelProviderConfig, observationRegistry: ObservationRegistry): ChatModel {
        val apiKey = config.apiKey
            ?: throw IllegalStateException("API key is required for OpenAI provider")

        val baseUrl = config.baseUrl ?: "https://api.openai.com"
        val timeout = Duration.ofSeconds(config.timeoutSeconds)

        val asyncClient = OpenAIOkHttpClientAsync.builder()
            .apiKey(apiKey)
            .baseUrl(baseUrl)
            .timeout(timeout)
            .maxRetries(3)
            .build()

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
                options = options.copy(temperature = it.temperature, maxTokens = it.maxTokens)
            }
        }

        if (outputSchema != null) {
            when (config.capabilities?.structuredOutput) {
                // null = undeclared, keep today's schema enforcement
                null, StructuredOutputSupport.JSON_SCHEMA -> options = options.copy(
                    responseFormatKind = OpenAiResponseFormatKind.JSON_SCHEMA,
                    outputSchema = outputSchema
                )
                // Schema-less JSON guarantee; the schema itself is conveyed via the prompt.
                StructuredOutputSupport.JSON_OBJECT -> options = options.copy(
                    responseFormatKind = OpenAiResponseFormatKind.JSON_OBJECT
                )
                // No API-level enforcement; OutputSchemaCompletionCheck nudges with the schema.
                StructuredOutputSupport.NONE -> Unit
            }
        }
        return options
    }
}
