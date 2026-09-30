package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.Generation
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation
import com.alibaba.dashscope.protocol.ConnectionOptions
import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.StructuredOutputSupport
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.tool.ToolCallback
import java.time.Duration

/**
 * DashScope (Aliyun Bailian) native-protocol implementation of [ChatModelFactory].
 *
 * The Bailian Java SDK has no Micrometer integration and derives its transport from process-wide
 * defaults unless told otherwise, so [create] pins the endpoint and the timeouts per configuration
 * through [ConnectionOptions] and passes the API key per request. [observationRegistry] is accepted
 * for interface parity but produces no GenAI spans.
 */
class DashScopeChatModelFactory : ChatModelFactory {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun supports(protocol: Protocol): Boolean = protocol == Protocol.DASHSCOPE

    override fun create(config: ModelProviderConfig, observationRegistry: ObservationRegistry): ChatModel {
        config.apiKey
            ?: throw IllegalStateException("API key is required for DashScope provider")
        val baseUrl = resolveBaseUrl(config)
        val timeout = Duration.ofSeconds(config.timeoutSeconds)
        val connectionOptions = ConnectionOptions.builder()
            .connectTimeout(timeout)
            .writeTimeout(timeout)
            .readTimeout(timeout)
            .build()

        val vision = config.capabilities?.vision == true
        if (vision) {
            logger.debug("Routing model {} to the DashScope multimodal endpoint", config.modelId)
        }
        val defaultOptions = optionsFor(config, emptyList(), null)
        return DashScopeChatModel(
            generation = if (vision) null else Generation(SDK_PROTOCOL_HTTP, baseUrl, connectionOptions),
            multiModalConversation =
            if (vision) MultiModalConversation(SDK_PROTOCOL_HTTP, baseUrl, connectionOptions) else null,
            defaultOptions = defaultOptions,
            vision = vision
        )
    }

    override fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>,
        outputSchema: String?
    ): ChatOptions = optionsFor(config, toolCallbacks, outputSchema)

    private fun optionsFor(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>,
        outputSchema: String?
    ): DashScopeChatOptions {
        val builder = DashScopeChatOptions.builder()
            .model(config.modelId)
            .apiKey(config.apiKey)
            .toolCallbacks(toolCallbacks)
            .resultFormat(RESULT_FORMAT_MESSAGE)

        config.options?.let { options ->
            builder.temperature(options.temperature)
            if (options.thinking) {
                builder.enableThinking(true)
                    .thinkingBudget(DEFAULT_THINKING_BUDGET_TOKENS)
                    // The budget is part of the output, so max_tokens has to leave room for it.
                    .maxTokens(maxOf(options.maxTokens, DEFAULT_THINKING_BUDGET_TOKENS + 1))
            } else {
                // Hybrid-reasoning qwen models reason by default; omitting enable_thinking leaves
                // it on, so disabling has to be sent explicitly.
                builder.enableThinking(false)
                // `reasoning_effort` and `thinking_budget` are rejected together by Bailian, so
                // effort only travels when thinking is off.
                options.effort?.let { builder.reasoningEffort(it) }
                builder.maxTokens(options.maxTokens)
            }
        }

        if (outputSchema != null) {
            when (config.capabilities?.structuredOutput) {
                // null = undeclared, keep schema enforcement as the default behavior.
                null, StructuredOutputSupport.JSON_SCHEMA ->
                    builder.outputSchema(outputSchema)
                        .responseFormatKind(DashScopeResponseFormatKind.JSON_SCHEMA)

                StructuredOutputSupport.JSON_OBJECT ->
                    builder.outputSchema(outputSchema)
                        .responseFormatKind(DashScopeResponseFormatKind.JSON_OBJECT)

                StructuredOutputSupport.NONE -> logger.debug(
                    "Model {} declares no API-level structured output; using prompt-based enforcement",
                    config.modelId
                )
            }
        }
        return builder.build()
    }

    private fun resolveBaseUrl(config: ModelProviderConfig): String {
        val raw = config.baseUrl?.takeIf { it.isNotBlank() } ?: DEFAULT_BASE_URL
        val normalized = raw.trimEnd('/')
        if (normalized.contains(COMPATIBLE_MODE_SEGMENT)) {
            logger.warn(
                "baseUrl {} is the OpenAI-compatible endpoint; select protocol OPENAI for it " +
                    "or point DASHSCOPE at the native /api/v1 endpoint",
                normalized
            )
        }
        return normalized
    }

    companion object {
        /** Bailian's native root; the SDK appends `/services/aigc/...` to it. */
        const val DEFAULT_BASE_URL = "https://dashscope.aliyuncs.com/api/v1"

        /** Path segment of the OpenAI-compatible endpoint, which belongs to Protocol.OPENAI. */
        const val COMPATIBLE_MODE_SEGMENT = "/compatible-mode/"

        /** Default thinking budget, matching the Anthropic adapter's value rather than Bailian's 131072. */
        const val DEFAULT_THINKING_BUDGET_TOKENS = 10_000

        /** `result_format=message` is required for tool calling. */
        const val RESULT_FORMAT_MESSAGE = "message"

        /** The SDK's protocol selector; `http` routes the configured baseUrl as-is. */
        const val SDK_PROTOCOL_HTTP = "http"
    }
}
