package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.web.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.DefaultChatOptions
import com.easy.easyai.api.llm.Message
import com.easy.easyai.api.llm.SystemMessage
import com.easy.easyai.api.llm.UserMessage
import com.easy.easyai.api.llm.Prompt
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import java.util.concurrent.ConcurrentHashMap

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

    // One provider client per config (data-class key: edits hash to a fresh key). Creating a
    // ChatModel per script call would build a new connection pool every time. Superseded entries
    // are never evicted on purpose: neither the ChatModel SPI nor the provider SDK clients expose
    // a close hook, and their idle pools/threads are reclaimed once the entry is dropped.
    private val chatModelCache = ConcurrentHashMap<ModelProviderConfig, ChatModel>()

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

        val chatModel = chatModelCache.computeIfAbsent(config) { factory.create(it) }

        val llmMessages = messages.map { msg ->
            when (msg.role.lowercase()) {
                "system" -> SystemMessage(msg.content) as Message
                "assistant" -> AssistantMessage(content = msg.content) as Message
                else -> UserMessage(msg.content) as Message
            }
        }

        val options = if (temperature != null || maxTokens != null) {
            DefaultChatOptions(
                temperature = temperature,
                maxTokens = maxTokens
            )
        } else {
            null
        }

        val prompt = if (options != null) Prompt(llmMessages, options) else Prompt(llmMessages)

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
