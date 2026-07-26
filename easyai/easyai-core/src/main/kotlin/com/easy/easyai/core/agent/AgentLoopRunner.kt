package com.easy.easyai.core.agent

import com.easy.easyai.core.event.*
import com.easy.easyai.core.memory.MemoryLoadResult
import com.easy.easyai.core.memory.MemoryLoader
import com.easy.easyai.core.memory.MemoryRef
import com.easy.easyai.core.model.*
import com.easy.easyai.core.prompt.PromptContext
import com.easy.easyai.core.tool.EasyAiToolCallback
import com.easy.easyai.core.tool.ToolDefinition
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.model.tool.StructuredOutputChatOptions
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds

/**
 * Handles LLM streaming calls with retry logic and response parsing.
 * Extracted from AgentLoop to reduce complexity.
 *
 * ChatModel is provided by [AgentService] and passed directly to this runner.
 * messageConverter is accessed via [AgentService].
 */
internal class AgentLoopRunner(
    private val context: AgentContext,
    private val chatModel: ChatModel,
    private val services: AgentService
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val logPrefix = agentLogPrefix(context.parentAgentId)

    companion object {
        /**
         * Maximum seconds to wait for the first chunk (Time-To-First-Token) before
         * considering the LLM stalled. Set higher than the inter-chunk stall timeout
         * because the LLM needs time to process the prompt (and potentially perform
         * extended thinking) before emitting the first token.
         */
        private const val FIRST_CHUNK_TIMEOUT_SECONDS = 240L

        /**
         * Maximum seconds to wait between consecutive stream chunks before considering
         * the LLM stalled. This is an application-level idle detection timeout, independent
         * of the HTTP read timeout configured on the model provider.
         */
        private const val STREAM_STALL_TIMEOUT_SECONDS = 120L
    }

    /** Cached memory content loaded once per session to avoid repeated file I/O on every turn. */
    private var cachedMemoryResult: MemoryLoadResult? = null
    private var memoryLoaded = false

    /** Whether the rendered system prompt has been persisted to the session (only on first turn). */
    private var systemMessagePersisted = false

    /** References collected during the last preparePrompt() call, available for the next assistant message. */
    private var lastReferences: ContextReferences? = null

    /**
     * Get the context references collected during the last [preparePrompt] call.
     * Returns null if no references were collected (e.g., no memory or instructions loaded).
     */
    fun getLastReferences(): ContextReferences? = lastReferences

    /**
     * Check if the exception indicates a timeout error that can be retried.
     * Delegates to [LlmErrorClassifier] which uses Spring AI's exception hierarchy
     * for more robust detection.
     */
    private fun isTimeoutException(e: Exception): Boolean {
        return LlmErrorClassifier.isTimeout(e)
    }

    suspend fun callLLMAndBuildResponse(
        transcript: MutableList<EasyAiMessage>,
        prompt: Prompt,
        messageId: String,
        turnId: Int,
        push: suspend (AgentEvent) -> Unit
    ): AssistantMessage {
        var lastResponseWithResults: ChatResponse? = null
        // Track the last chunk carrying usage metadata (typically the message_delta event).
        // Anthropic's message_delta has empty text but carries output token counts in its
        // ChatResponse.metadata.usage. Without separate tracking this chunk gets filtered
        // by the empty-content skip below and usage is lost for text-only responses.
        var lastResponseWithUsage: ChatResponse? = null
        val fullText = StringBuilder()
        val fullThinking = StringBuilder()
        var thinkingEnded = false
        var thinkingStartTime = 0L
        var thinkingDuration = 0L
        var textStartTime = 0L
        var chunkCount = 0
        var retryCount = 0
        var streamDuration: Long

        while (true) {
            var streamStartTime = 0L
            try {
                logger.debug("${logPrefix}[Turn {}] Starting LLM stream call (model={}, attempt={}/{}, tools={}, subAgents={}, skills={})",
                    turnId, context.modelId, retryCount + 1, context.maxRetries + 1,
                    context.tools.size, context.subAgents.size, context.skills.size)
                streamStartTime = System.currentTimeMillis()
                chunkCount = 0

                // Channel-based streaming with per-chunk stall detection.
                // Producer coroutine feeds LLM chunks into the channel;
                // consumer receives with a timeout to detect stalled streams.
                // The stall timeout is independent of the HTTP read timeout and
                // catches the case where the LLM sends partial data then hangs.
                coroutineScope {
                    val channel = Channel<ChatResponse>(Channel.UNLIMITED)
                    val producerJob = launch {
                        try {
                            chatModel.stream(prompt).asFlow().collect { chunk ->
                                channel.send(chunk)
                            }
                        } finally {
                            channel.close()
                        }
                    }

                    // Track whether any chunk with actual content was received in the current
                    // receive cycle. Empty chunks (keepalive/ping from the SSE layer) should
                    // NOT reset the stall timer — only meaningful content counts.
                    var receivedContentChunk = false

                    while (true) {
                        // Use a longer timeout for the first chunk (TTFT) since the LLM
                        // needs time to process the prompt before emitting the first token.
                        // The timeout only resets when we receive a chunk with actual content;
                        // empty SSE keepalive events are drained without resetting the timer.
                        val baseTimeout = if (!receivedContentChunk && chunkCount == 0) {
                            (FIRST_CHUNK_TIMEOUT_SECONDS * 1000L).milliseconds
                        } else if (!receivedContentChunk) {
                            (STREAM_STALL_TIMEOUT_SECONDS * 1000L).milliseconds
                        } else {
                            // Already received content in this cycle, use stall timeout
                            (STREAM_STALL_TIMEOUT_SECONDS * 1000L).milliseconds
                        }
                        val result = withTimeoutOrNull(baseTimeout) {
                            channel.receiveCatching()
                        }
                        if (result == null) {
                            val phase = if (chunkCount == 0) "first token (TTFT)" else "subsequent chunk"
                            val timeoutSec = if (chunkCount == 0) FIRST_CHUNK_TIMEOUT_SECONDS else STREAM_STALL_TIMEOUT_SECONDS
                            producerJob.cancel()
                            throw TimeoutException(
                                "LLM stream stalled: no $phase received within ${timeoutSec}s"
                            )
                        }
                        val chunk = result.getOrNull() ?: break // Channel closed (normal or error)
                        val results = chunk.results

                        // Skip empty chunks (SSE keepalive/ping) without resetting stall timer.
                        // Only chunks with actual content indicate the LLM is still making progress.
                        // However, capture usage metadata before skipping — the Anthropic message_delta
                        // event carries token usage but has empty text content.
                        if (results.isEmpty() || results.all { r -> r.output.text.isNullOrEmpty() && r.output.toolCalls.isEmpty() }) {
                            // Anthropic sends a signature chunk (empty text, metadata["signature"])
                            // at the end of the thinking block, right before tool call generation
                            // begins. Use it as the "thinking ended" signal so the frontend timer
                            // stops immediately instead of running through the entire tool argument
                            // generation phase (during which the adapter emits no chunks at all).
                            if (!thinkingEnded && fullThinking.isNotEmpty() &&
                                results.any { r -> r.output.metadata.containsKey("signature") }) {
                                thinkingDuration = System.currentTimeMillis() - thinkingStartTime
                                push(ThinkingEndEvent(messageId, turnId, context.sessionId ?: "default", thinkingDuration))
                                thinkingEnded = true
                            }
                            lastResponseWithUsage = chunk
                            continue
                        }

                        // Content-bearing chunk: reset stall detection state
                        receivedContentChunk = true
                        chunkCount++
                        lastResponseWithResults = chunk

                        for (r in results) {
                            val output = r.output
                            val text = output.text
                            val metadata = output.metadata

                            if (!text.isNullOrEmpty()) {
                                if (metadata.containsKey("signature") || metadata.containsKey("thinking")) {
                                    if (fullThinking.isEmpty()) {
                                        thinkingStartTime = System.currentTimeMillis()
                                    }
                                    fullThinking.append(text)
                                    push(ThinkingUpdateEvent(messageId, text, turnId, context.sessionId ?: "default"))
                                } else {
                                    if (fullThinking.isNotEmpty() && !thinkingEnded) {
                                        thinkingDuration = System.currentTimeMillis() - thinkingStartTime
                                        push(ThinkingEndEvent(messageId, turnId, context.sessionId ?: "default", thinkingDuration))
                                        thinkingEnded = true
                                    }
                                    if (fullText.isEmpty()) {
                                        textStartTime = System.currentTimeMillis()
                                    }
                                    fullText.append(text)
                                    push(MessageUpdateEvent(messageId, text, turnId, context.sessionId ?: "default"))
                                }
                            }
                        }

                        // When the model transitions from thinking directly to tool call generation
                        // (no intermediate text), emit ThinkingEndEvent as soon as the first tool
                        // call chunk appears. Without this, the frontend thinking timer keeps
                        // running during the entire tool argument generation phase.
                        if (!thinkingEnded && fullThinking.isNotEmpty() &&
                            results.any { it.output.toolCalls.isNotEmpty() }) {
                            thinkingDuration = System.currentTimeMillis() - thinkingStartTime
                            push(ThinkingEndEvent(messageId, turnId, context.sessionId ?: "default", thinkingDuration))
                            thinkingEnded = true
                        }
                    }
                }
                streamDuration = System.currentTimeMillis() - streamStartTime
                logger.debug("${logPrefix}[Turn {}] LLM stream completed in {}ms, chunks={}, textLen={}, thinkingLen={}",
                    turnId, streamDuration, chunkCount, fullText.length, fullThinking.length)
                break // Success, exit retry loop
            } catch (e: Exception) {
                if (e is CancellationException) {
                    // Graceful abort: save partial response before propagating cancellation
                    val partialContent = mutableListOf<ContentBlock>()
                    if (fullThinking.isNotEmpty()) {
                        partialContent.add(ThinkingContent(thinking = fullThinking.toString()))
                    }
                    if (fullText.isNotEmpty()) {
                        partialContent.add(TextContent(fullText.toString()))
                    }
                    partialContent.add(TextContent("\n\n[Response interrupted by user]"))

                    val partialMessage = AssistantMessage(
                        id = messageId,
                        content = partialContent,
                        stopReason = StopReason.ABORTED,
                        usage = Usage()
                    )
                    transcript.add(partialMessage)
                    services.messageListener?.onMessageAdded(listOf(partialMessage))
                    logger.info("${logPrefix}[Turn {}] Aborted: saved partial message ({} chars text, {} chars thinking)",
                        turnId, fullText.length, fullThinking.length)
                    throw e
                }
                val elapsed = System.currentTimeMillis() - streamStartTime
                logger.debug("${logPrefix}[Turn {}] LLM stream threw exception after {}ms, {} chunks received: {}",
                    turnId, elapsed, chunkCount, e.message)
                // Check if the coroutine has been cancelled (e.g., by user abort).
                // Without this check, a connection-close exception caused by cancellation
                // would be misclassified as a retriable timeout, spawning new LLM calls
                // that waste resources and delay shutdown.
                if (!currentCoroutineContext().isActive) {
                    logger.info("${logPrefix}[Turn {}] Coroutine cancelled, skipping retry (exception: {})",
                        turnId, e.message)
                    throw CancellationException("LLM call interrupted by cancellation").also {
                        it.initCause(e)
                    }
                }
                if (retryCount < context.maxRetries && isTimeoutException(e)) {
                    retryCount++
                    val backoffMs = retryCount * 1000L
                    logger.warn("${logPrefix}LLM call timed out, retrying ({}/{}) after {}ms", retryCount, context.maxRetries, backoffMs)
                    push(RetryEvent(messageId, retryCount, context.maxRetries, backoffMs, turnId, context.sessionId ?: "default"))
                    delay(backoffMs.milliseconds)
                    // Second cancellation check: delay() may have completed before
                    // the cancellation signal arrived, or SupervisorJob cancellation
                    // propagation may be delayed.
                    if (!currentCoroutineContext().isActive) {
                        logger.info("${logPrefix}[Turn {}] Coroutine cancelled during retry backoff, aborting retry", turnId)
                        throw CancellationException("LLM call interrupted during retry backoff")
                    }
                    // Reset accumulators for retry
                    lastResponseWithResults = null
                    lastResponseWithUsage = null
                    fullText.clear()
                    fullThinking.clear()
                    thinkingEnded = false
                    thinkingStartTime = 0L
                    thinkingDuration = 0L
                    textStartTime = 0L
                } else {
                    logger.error("${logPrefix}LLM call failed after {} retries: {}", retryCount, e.message)
                    throw e
                }
            }
        }

        // Build the assistant message
        val finalResponse = lastResponseWithResults
        val responseToolCalls = finalResponse?.results
            ?.mapNotNull { it.output.toolCalls }
            ?.flatten()
            ?.map { tc ->
                ToolCallContent(
                    id = tc.id,
                    name = tc.name,
                    arguments = tc.arguments
                )
            }
            .orEmpty()

        // Ensure ThinkingEndEvent is sent if thinking content exists but hasn't ended
        if (fullThinking.isNotEmpty() && !thinkingEnded) {
            thinkingDuration = System.currentTimeMillis() - thinkingStartTime
            push(ThinkingEndEvent(messageId, turnId, context.sessionId ?: "default", thinkingDuration))
        }
        val textDuration = if (fullText.isNotEmpty()) System.currentTimeMillis() - textStartTime else 0L

        val contentBlocks = mutableListOf<ContentBlock>()
        if (fullThinking.isNotEmpty()) {
            contentBlocks.add(ThinkingContent(thinking = fullThinking.toString(), durationMs = thinkingDuration.takeIf { it > 0 }))
        }
        if (fullText.isNotEmpty()) {
            contentBlocks.add(TextContent(text = fullText.toString(), durationMs = textDuration.takeIf { it > 0 }))
        }
        contentBlocks.addAll(responseToolCalls)

        val finishReasonStr = finalResponse?.results?.firstOrNull()?.metadata?.finishReason
            ?: lastResponseWithUsage?.results?.firstOrNull()?.metadata?.finishReason
        // Prefer usage from the dedicated usage chunk (message_delta), fall back to
        // lastResponseWithResults for providers that embed usage in a content-bearing chunk.
        val usage = lastResponseWithUsage?.metadata?.usage
            ?: finalResponse?.metadata?.usage

        return AssistantMessage(
            id = messageId,
            content = contentBlocks,
            stopReason = when (finishReasonStr) {
                "stop" -> StopReason.STOP
                "length" -> StopReason.LENGTH
                "tool_calls" -> StopReason.TOOL_USE
                else -> StopReason.STOP
            },
            usage = usage?.let { u ->
                Usage(
                    inputTokens = u.promptTokens,
                    outputTokens = u.completionTokens,
                    cacheReadTokens = u.cacheReadInputTokens?.toInt() ?: 0,
                    cacheWriteTokens = u.cacheWriteInputTokens?.toInt() ?: 0,
                    durationMs = streamDuration
                )
            } ?: Usage(durationMs = streamDuration)
        )
    }

    /**
     * Prepare the Spring AI Prompt with system prompt and tool callbacks.
     * System prompt is built at usage time via PromptTemplateService.
     */
    suspend fun preparePrompt(
        transformedMessages: List<EasyAiMessage>,
        tools: List<ToolDefinition>
    ): Prompt {
        val springAiMessages = services.messageConverter.toSpringAiMessages(transformedMessages)
        val toolCallbacks = tools.map { EasyAiToolCallback(it) }

        // Build ChatOptions at usage time with real toolCallbacks
        val baseChatOptions = context.modelConfig?.let { config ->
            services.buildChatOptions(
                config = config,
                toolCallbacks = toolCallbacks,
                additionalOptions = context.options ?: emptyMap()
            )
        } ?: ChatOptions.builder().model(context.modelId).build()

        // Inject outputSchema for model-level structured output enforcement.
        // Protocol-specific options (OpenAI, Anthropic) implement StructuredOutputChatOptions,
        // which maps outputSchema to ResponseFormat(type=JSON_SCHEMA) / OutputConfig+JsonOutputFormat.
        val chatOptions = if (context.outputSchema != null && baseChatOptions is StructuredOutputChatOptions) {
            baseChatOptions.mutate().outputSchema(context.outputSchema).build()
        } else {
            baseChatOptions
        }

        // Load memory index once per session (cached after first load)
        val memoryContent = if (!memoryLoaded) {
            try {
                services.memoryStore?.let { store ->
                    MemoryLoader(store).loadSystemMemoryWithRefs(context).also {
                        cachedMemoryResult = it
                        memoryLoaded = true  // Only cache on success
                    }
                }?.formattedContent
            } catch (e: Exception) {
                logger.warn("Memory load failed, will retry next turn: {}", e.message)
                null
            }
        } else {
            cachedMemoryResult?.formattedContent
        }

        // Build system prompt at usage time via PromptTemplateService
        val toolsData = context.tools.map { tool ->
            mapOf<String, Any?>("name" to tool.name, "description" to tool.description)
        }
        val promptContext = PromptContext(
            customInstructions = context.customInstructions,
            protocol = context.protocol,
            modelId = context.modelId,
            skills = context.skills,
            subAgents = context.subAgents,
            teamMembers = context.teamMembers,
            teamStatusSummary = context.teamStatusSummary,
            instructions = context.instructions,
            cwd = context.projectPath?.toString(),
            memory = memoryContent,
            tools = toolsData,
            outputSchema = context.outputSchema,
            inputVariables = context.inputVariables,
            scriptLlmAvailable = context.scriptEnv.isNotEmpty()
        )
        val systemPromptText = services.promptTemplateService.build(
            context.promptTemplate, promptContext
        )

        // Persist the rendered system prompt to session on first turn
        if (!systemMessagePersisted && systemPromptText.isNotBlank()) {
            val systemMsg = SystemMessage(text = systemPromptText)
            services.messageListener?.onMessageAdded(listOf(systemMsg))
            systemMessagePersisted = true
        }

        // Collect rule references for the next assistant message
        val ruleRefs = context.instructions.map { instr ->
            RuleRef(name = instr.name, source = instr.source.name)
        }
        lastReferences = if (ruleRefs.isNotEmpty()) {
            ContextReferences(memories = emptyList(), rules = ruleRefs)
        } else {
            null
        }

        val allSpringAiMessages = if (systemPromptText.isNotBlank()) {
            listOf(SystemMessage(systemPromptText)) + springAiMessages
        } else {
            springAiMessages
        }

        return Prompt(allSpringAiMessages, chatOptions)
    }

    /**
     * Get memory refs that were accessed by memory_read / memory_search tools
     * during the current turn.
     */
    fun getMemoryRefs(): List<MemoryRef> = context.memoryAccessTracker.getAccessedRefs()
}