package com.easy.easyai.autoconfigure.anthropic

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.anthropic.AnthropicSetup
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.tool.ToolCallback
import java.time.Duration

/**
 * Anthropic implementation of ChatModelFactory.
 * Creates AnthropicChatModel instances and builds AnthropicChatOptions based on the provided configuration.
 */
class AnthropicChatModelFactory : ChatModelFactory {
    override fun supports(protocol: Protocol): Boolean = protocol == Protocol.ANTHROPIC

    override fun create(config: ModelProviderConfig, observationRegistry: ObservationRegistry): ChatModel {
        val apiKey = config.apiKey
            ?: throw IllegalStateException("API key is required for Anthropic provider")

        val baseUrl = config.baseUrl ?: "https://api.anthropic.com"
        val timeout = Duration.ofSeconds(config.timeoutSeconds)

        val syncClient = AnthropicSetup.setupSyncClient(
            baseUrl,
            apiKey,
            timeout,
            2,      // maxRetries
            null,   // proxy
            null,   // customHeaders
            observationRegistry,
            null    // meterRegistry
        )

        val asyncClient = AnthropicSetup.setupAsyncClient(
            baseUrl,
            apiKey,
            timeout,
            2,      // maxRetries
            null,   // proxy
            null,   // customHeaders
            observationRegistry,
            null    // meterRegistry
        )

        val defaultOptions = AnthropicChatOptions.builder().model(config.modelId).build()

        return AnthropicChatModel.builder()
            .anthropicClient(syncClient)
            .anthropicClientAsync(asyncClient)
            .options(defaultOptions)
            .observationRegistry(observationRegistry)
            .build()
    }

    override fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>
    ): ChatOptions {
        val builder = AnthropicChatOptions.builder()
            .model(config.modelId)
            .toolCallbacks(toolCallbacks)

        config.options?.let {
            if (it.thinking) {
                // Anthropic requires temperature=1 when thinking is enabled
                builder.thinkingEnabled(DEFAULT_THINKING_BUDGET_TOKENS)
                // max_tokens must be > budget_tokens; enforce a safe floor
                builder.maxTokens(maxOf(it.maxTokens, DEFAULT_THINKING_BUDGET_TOKENS.toInt() + 1))
            } else {
                builder.temperature(it.temperature)
                builder.maxTokens(it.maxTokens)
            }
        }
        return builder.build()
    }

    companion object {
        /** Default token budget for extended thinking when enabled. */
        private const val DEFAULT_THINKING_BUDGET_TOKENS = 10_000L
    }
}
