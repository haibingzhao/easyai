package com.easy.easyai.autoconfigure.anthropic

import com.anthropic.models.messages.OutputConfig
import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
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

    private val logger = LoggerFactory.getLogger(javaClass)

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

        val chatModel = AnthropicChatModel.builder()
            .anthropicClient(syncClient)
            .anthropicClientAsync(asyncClient)
            .options(defaultOptions)
            .observationRegistry(observationRegistry)
            .build()

        return UsageCorrectingAnthropicChatModel(chatModel)
    }

    override fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>,
        outputSchema: String?
    ): ChatOptions {
        val builder = AnthropicChatOptions.builder()
            .model(config.modelId)
            .toolCallbacks(toolCallbacks)

        config.options?.let {
            // 1. Thinking: pass thinking.budget_tokens when enabled
            if (it.thinking) {
                builder.thinkingEnabled(DEFAULT_THINKING_BUDGET_TOKENS)
                // max_tokens must be > budget_tokens; enforce a safe floor
                builder.maxTokens(maxOf(it.maxTokens, DEFAULT_THINKING_BUDGET_TOKENS.toInt() + 1))
            }

            // 2. Effort: independent of thinking, always applied when set
            val effortValue = it.effort
            if (effortValue != null) {
                builder.effort(mapToOutputConfigEffort(effortValue))
            }

            // 3. Temperature/maxTokens: only when thinking is not active
            if (!it.thinking) {
                builder.temperature(it.temperature)
                builder.maxTokens(it.maxTokens)
            }
        }

        // 4. Structured output: applied only when the model declares support. The Anthropic
        // SDK's JsonOutputFormat hardcodes type=json_schema, so JSON_OBJECT is not
        // expressible here and degrades to the prompt-based enforcement path.
        if (outputSchema != null) {
            when (config.capabilities?.structuredOutput) {
                // null = undeclared, keep today's schema enforcement
                null, StructuredOutputSupport.JSON_SCHEMA -> builder.outputSchema(outputSchema)
                StructuredOutputSupport.JSON_OBJECT -> logger.debug(
                    "Model {} declares JSON_OBJECT-only structured output, which the Anthropic protocol cannot express; using prompt-based enforcement",
                    config.modelId
                )
                StructuredOutputSupport.NONE -> Unit
            }
        }
        return builder.build()
    }

    companion object {
        /** Default token budget for extended thinking when enabled. */
        private const val DEFAULT_THINKING_BUDGET_TOKENS = 10_000L

        /** Maps effort string to Anthropic OutputConfig.Effort enum. */
        private fun mapToOutputConfigEffort(effort: String): OutputConfig.Effort = when (effort.lowercase()) {
            "low" -> OutputConfig.Effort.LOW
            "medium" -> OutputConfig.Effort.MEDIUM
            "high" -> OutputConfig.Effort.HIGH
            "xhigh" -> OutputConfig.Effort.XHIGH
            "max" -> OutputConfig.Effort.MAX
            else -> OutputConfig.Effort.HIGH
        }
    }
}
