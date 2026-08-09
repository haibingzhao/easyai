package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.ai.openai.OpenAiChatOptions
import org.springframework.ai.openai.StreamClosingOpenAiChatModel
import org.springframework.ai.openai.setup.OpenAiSetup
import org.springframework.ai.tool.ToolCallback
import java.time.Duration

/**
 * OpenAI implementation of ChatModelFactory.
 * Creates OpenAiChatModel instances and builds OpenAiChatOptions based on the provided configuration.
 *
 * The returned model is wrapped in [StreamClosingOpenAiChatModel] to close the SDK stream on
 * downstream cancellation (workaround for spring-projects/spring-ai#6654, which otherwise leaks
 * the underlying OkHttp connection when a stream is abandoned, e.g. by the stall timeout).
 */
class OpenAiChatModelFactory : ChatModelFactory {
    override fun supports(protocol: Protocol): Boolean = protocol == Protocol.OPENAI

    override fun create(config: ModelProviderConfig, observationRegistry: ObservationRegistry): ChatModel {
        val apiKey = config.apiKey
            ?: throw IllegalStateException("API key is required for OpenAI provider")

        val baseUrl = config.baseUrl ?: "https://api.openai.com"
        val timeout = Duration.ofSeconds(config.timeoutSeconds)

        val syncClient = OpenAiSetup.setupSyncClient(
            baseUrl,
            apiKey,
            null, // credential
            null, // azureDeploymentName
            null, // azureOpenAiServiceVersion
            null, // organizationId
            false, // isAzure
            false, // isGitHubModels
            config.modelId,
            timeout,
            3, // maxRetries
            null, // proxy
            null, // customHeaders
            observationRegistry,
            null, // meterRegistry
            emptyList() // httpClientCustomizers
        )

        val asyncClient = OpenAiSetup.setupAsyncClient(
            baseUrl,
            apiKey,
            null, // credential
            null, // azureDeploymentName
            null, // azureOpenAiServiceVersion
            null, // organizationId
            false, // isAzure
            false, // isGitHubModels
            config.modelId,
            timeout,
            3, // maxRetries
            null, // proxy
            null, // customHeaders
            observationRegistry,
            null, // meterRegistry
            emptyList() // httpClientCustomizers
        )

        val defaultOptions = OpenAiChatOptions.builder().model(config.modelId).build()

        val chatModel = OpenAiChatModel.builder()
            .openAiClient(syncClient)
            .openAiClientAsync(asyncClient)
            .options(defaultOptions)
            .observationRegistry(observationRegistry)
            .build()

        return StreamClosingOpenAiChatModel(chatModel, asyncClient, observationRegistry)
    }

    override fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>
    ): ChatOptions {
        val builder = OpenAiChatOptions.builder()
            .model(config.modelId)
            .toolCallbacks(toolCallbacks)
        config.options?.let {
            if (it.thinking) {
                // OpenAI reasoning models (o-series) do not support temperature/maxTokens;
                // they use maxCompletionTokens and reasoningEffort instead.
                builder.reasoningEffort("high")
                builder.maxCompletionTokens(it.maxTokens)
            } else {
                builder.temperature(it.temperature)
                builder.maxTokens(it.maxTokens)
            }
        }
        return builder.build()
    }
}
