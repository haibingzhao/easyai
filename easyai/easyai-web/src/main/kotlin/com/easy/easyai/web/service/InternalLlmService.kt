package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.web.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException

/**
 * Service for internal LLM processing.
 * Handles synchronous LLM calls from scripts via the internal endpoint.
 * Only registered when easyai.script-llm.enabled=true.
 */
@Service
@ConditionalOnProperty(prefix = "easyai.script-llm", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class InternalLlmService(
    private val configStore: ModelProviderConfigStore,
    private val modelFactories: List<ChatModelFactory>
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Process a single LLM request synchronously.
     */
    suspend fun process(
        userId: String,
        modelConfigId: String,
        messages: List<LlmMessage>,
        temperature: Double? = null,
        maxTokens: Int? = null
    ): InternalLlmResponse {
        val config = configStore.getConfig(modelConfigId, userId)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Model config not found: $modelConfigId")

        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No ChatModelFactory for protocol: ${config.protocol}")

        val chatModel = factory.create(config)

        val springAiMessages = messages.map { msg ->
            when (msg.role.lowercase()) {
                "system" -> SystemMessage(msg.content) as Message
                "assistant" -> AssistantMessage(msg.content) as Message
                else -> UserMessage(msg.content) as Message
            }
        }

        val options = if (temperature != null || maxTokens != null) {
            ChatOptions.builder()
                .apply {
                    temperature?.let { temperature(it) }
                    maxTokens?.let { maxTokens(it) }
                }
                .build()
        } else {
            null
        }

        val prompt = if (options != null) Prompt(springAiMessages, options) else Prompt(springAiMessages)

        val response = withContext(Dispatchers.IO) {
            chatModel.call(prompt)
        }

        val content = response.result?.output?.text ?: ""
        val usage = response.metadata.usage

        return InternalLlmResponse(
            content = content,
            model = config.modelId,
            inputTokens = usage.promptTokens,
            outputTokens = usage.completionTokens
        )
    }

    /**
     * Process multiple items concurrently with controlled concurrency.
     */
    suspend fun batchProcess(
        userId: String,
        modelConfigId: String,
        instruction: String,
        items: List<BatchItem>,
        concurrency: Int = DEFAULT_CONCURRENCY
    ): BatchLlmResponse {
        val startTime = System.currentTimeMillis()
        val semaphore = Semaphore(concurrency.coerceIn(1, MAX_CONCURRENCY))

        val results = coroutineScope {
            items.map { item ->
                async {
                    semaphore.withPermit {
                        try {
                            val response = process(
                                userId = userId,
                                modelConfigId = modelConfigId,
                                messages = listOf(
                                    LlmMessage(role = "system", content = instruction),
                                    LlmMessage(role = "user", content = item.content)
                                )
                            )
                            BatchResult(id = item.id, content = response.content)
                        } catch (e: Exception) {
                            logger.warn("Batch item {} failed: {}", item.id, e.message)
                            BatchResult(id = item.id, error = e.message ?: "Unknown error")
                        }
                    }
                }
            }.awaitAll()
        }

        return BatchLlmResponse(
            results = results,
            totalDurationMs = System.currentTimeMillis() - startTime
        )
    }

    companion object {
        private const val DEFAULT_CONCURRENCY = 5
        private const val MAX_CONCURRENCY = 10
    }
}
