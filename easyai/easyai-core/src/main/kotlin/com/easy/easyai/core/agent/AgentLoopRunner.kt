package com.easy.easyai.core.agent

import com.easy.easyai.api.llm.*
import com.easy.easyai.api.model.SamplingDecision
import com.easy.easyai.api.model.SamplingIntent
import com.easy.easyai.api.model.SamplingPolicy
import com.easy.easyai.api.model.SamplingTrace
import com.easy.easyai.api.model.TemperatureSource
import com.easy.easyai.core.event.*
import com.easy.easyai.core.memory.MemoryRef
import com.easy.easyai.core.message.CommandMessageProjection
import com.easy.easyai.core.model.*
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.prompt.PromptContext
import com.easy.easyai.core.resilience.CircuitBreakerOpenException
import com.easy.easyai.core.resilience.LlmCircuitBreakerRegistry
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolDefinition
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.reactive.asFlow
import org.slf4j.LoggerFactory
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.milliseconds
import com.easy.easyai.api.llm.SystemMessage as LlmSystemMessage

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
    private val logPrefix = agentLogPrefix(context)

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

    /** Whether the rendered system prompt has been persisted to the session (only on first turn). */
    private var systemMessagePersisted = false

    /** References collected during the last preparePrompt() call, available for the next assistant message. */
    private var lastReferences: ContextReferences? = null

    /**
     * Sampling decided for the last preparePrompt() call, attached to the assistant message that
     * follows it. Set before the request goes out so a failed turn still records what was asked for.
     */
    private var lastSampling: SamplingDecision? = null

    /** The intent [lastSampling] was taken under — a repair turn overrides the run's own. */
    private var lastSamplingIntent: SamplingIntent = SamplingIntent.FOLLOW_CONFIG

    /**
     * Whether this run has already said out loud that a pinned temperature did not survive into the
     * request. One line per run is enough: every turn of a reasoning-mode row drops it the same way,
     * and a per-turn warning would bury the fact that the whole run is unconstrained.
     */
    private var pinnedSamplingDroppedLogged = false

    /** When true, next preparePrompt() applies API-level structured output regardless of multiTurn mode. */
    @Volatile
    var forceStructuredOutput = false
        private set

    fun enableForcedStructuredOutput() { forceStructuredOutput = true }

    /**
     * Latched for the last structured-output nudge: that turn sends no tool schemas and pins
     * sampling, so the final chance to produce conforming JSON is the cheapest and least variable
     * one. Same one-way pattern as [forceStructuredOutput] — the runner dies with the run.
     */
    @Volatile
    var repairTurn = false
        private set

    fun beginRepairTurn() { repairTurn = true }

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
        // The last non-null finish reason seen anywhere in the stream. Providers send it on the
        // choice-closing frame and then a trailing usage-only frame whose `choices` is empty and
        // whose reason is null, so reading only the final frame loses the reason.
        var lastFinishReason: String? = null
        // The last chunk that carried tool calls. Every adapter assembles the complete call set
        // before emitting it, but that frame is not guaranteed to be the last content-bearing one.
        var lastToolCallResponse: ChatResponse? = null
        val fullText = StringBuilder()
        val fullThinking = StringBuilder()
        var thinkingEnded = false
        var thinkingStartTime = 0L
        var thinkingDuration = 0L
        // Anthropic emits the thinking-block signature as a dedicated empty-text chunk after the
        // reasoning deltas; persisting it lets thinkingHistoryEnabled replay the block later.
        // More than one signature means more than one thinking block in this turn — the merged
        // text then matches no single signature, so replay is disabled for the turn.
        var thinkingSignature: String? = null
        var thinkingSignatures = 0
        var textStartTime = 0L
        var chunkCount = 0
        var retryCount = 0
        var streamDuration: Long
        // Endpoint circuit breaker (null when disabled or no model config).
        // When OPEN, acquirePermission() fails fast with CircuitBreakerOpenException, which the
        // catch below turns into an LlmEndpointUnavailableException without consuming retry budget.
        val breaker = LlmCircuitBreakerRegistry.forContext(context)

        while (true) {
            var streamStartTime = 0L
            try {
                // Timestamped before the breaker gate: the catch below reports elapsed time, and a
                // rejection thrown by acquirePermission would otherwise subtract an unset start time
                // and log an absurd ~epoch-scale duration.
                streamStartTime = System.currentTimeMillis()
                // Inside the try: an OPEN breaker must reach the classification chain below so it
                // surfaces as a readable endpoint-unavailable error instead of escaping as a raw
                // CircuitBreakerOpenException that AgentRunner can only report as a generic "error".
                breaker?.acquirePermission()
                logger.debug("${logPrefix}[Turn {}] Starting LLM stream call (agent={}, model={}, attempt={}/{}, tools={}, subAgents={}, skills={})",
                    turnId,context.agentId, context.modelId, retryCount + 1, context.maxRetries + 1,
                    context.tools.size, context.subAgents.size, context.skills.size)
                chunkCount = 0
                lastFinishReason = null
                lastToolCallResponse = null

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

                        // The finish reason rides on the frame that closes the choice; providers then send a
                        // usage-only frame with no choices and no reason, so it has to be kept across the
                        // stream instead of read off whichever chunk arrived last.
                        results.firstNotNullOfOrNull { it.metadata.finishReason }?.let { lastFinishReason = it }

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
                            (results.firstNotNullOfOrNull { it.output.metadata["signature"] as? String })
                                ?.takeIf { it.isNotBlank() }
                                ?.let { sig ->
                                    thinkingSignatures++
                                    thinkingSignature = if (thinkingSignatures == 1) sig else null
                                }
                            lastResponseWithUsage = chunk
                            continue
                        }

                        // Content-bearing chunk: reset stall detection state
                        receivedContentChunk = true
                        chunkCount++
                        lastResponseWithResults = chunk
                        // A frame carrying tool calls carries the complete set (all three protocol
                        // adapters assemble before emitting), but it is not necessarily the last
                        // content-bearing frame — a gateway may still stream text after it.
                        if (results.any { it.output.toolCalls.isNotEmpty() }) {
                            lastToolCallResponse = chunk
                        }

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
                breaker?.recordSuccess()
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
                // An OPEN breaker is a verdict, not a request failure: never retry it (the next
                // acquirePermission would just be rejected again) and never let it reach
                // recordFailure below (that would double the cooldown for a non-request event).
                // Checked first so that guarantee does not depend on isEndpointOutage's matching.
                if (e is CircuitBreakerOpenException) {
                    logger.warn("${logPrefix}[Turn {}] LLM endpoint circuit open, failing fast (no retry): {}",
                        turnId, e.message)
                    // How long the breaker still blocks is the one actionable thing about an outage;
                    // HALF_OPEN rejections report 0 ("re-check shortly"), so the hint degrades to a
                    // plain retry.
                    val retryHint = if (e.retryAfterSeconds > 0) "，约 ${e.retryAfterSeconds}s 后可重试" else "，请稍后重试"
                    throw LlmEndpointUnavailableException(
                        "模型端点暂时不可用（连续失败已进入熔断保护）$retryHint", e
                    )
                }
                // A spent quota fails identically on every attempt until it resets, and the
                // gateway answers it immediately (no partial stream to save). Checked before the
                // outage counter so a healthy-but-unfunded endpoint never trips the breaker.
                if (LlmErrorClassifier.isQuotaExhausted(e)) {
                    val reset = LlmErrorClassifier.quotaResetHint(e)
                    val retryHint = if (reset != null) "，配额将在 $reset 重置" else "，请检查套餐额度或购买用量包"
                    logger.warn("${logPrefix}[Turn {}] LLM quota exhausted, failing fast (no retry): {}",
                        turnId, e.message)
                    throw QuotaExhaustedException("模型额度已耗尽$retryHint", e)
                }
                // A refused response format is a wrong capability on the model row, not a fault of this
                // request: every replay of the same request re-triggers the same 400. Say what to change
                // instead of dropping the format and letting the run continue on a quieter path, which is
                // how a mis-declared row stayed invisible behind "400: Unknown".
                if (LlmErrorClassifier.isResponseFormatRejected(e)) {
                    logger.warn(
                        "${logPrefix}[Turn {}] Gateway refused the response format declared for model {} (no retry): {}",
                        turnId, context.modelId, e.message
                    )
                    throw ResponseFormatUnsupportedException(
                        "模型 ${context.modelId} 不支持本次请求声明的结构化输出格式，请在「设置 → 模型」把该模型的" +
                            "结构化输出能力改为「仅 JSON 对象」或「不支持」后重试。网关原话：${e.message}", e
                    )
                }
                // Report outage-class failures (connection errors, stalls, 5xx) to the
                // endpoint circuit breaker. Rate limits and client errors are filtered
                // out by isEndpointOutage and never count.
                if (LlmErrorClassifier.isEndpointOutage(e)) {
                    breaker?.recordFailure()
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
                    thinkingSignature = null
                    thinkingSignatures = 0
                    thinkingEnded = false
                    thinkingStartTime = 0L
                    thinkingDuration = 0L
                    textStartTime = 0L
                } else if (LlmErrorClassifier.isContentFiltered(e)) {
                    // Output blocked by the provider's content safety policy. Deterministic
                    // rejection: never retried, surfaced with an actionable user message.
                    logger.warn("${logPrefix}[Turn {}] LLM output blocked by content safety policy (no retry): {}",
                        turnId, e.message)
                    // The gateway aborts mid-stream, so whatever was generated before the
                    // filter tripped has already been pushed to the frontend as live chunks.
                    // Persist it (mirroring the abort path above) so a session reload keeps
                    // the partial analysis instead of only the error message.
                    if (fullText.isNotEmpty() || fullThinking.isNotEmpty()) {
                        if (fullThinking.isNotEmpty() && !thinkingEnded) {
                            thinkingDuration = System.currentTimeMillis() - thinkingStartTime
                            push(ThinkingEndEvent(messageId, turnId, context.sessionId ?: "default", thinkingDuration))
                        }
                        val partialContent = mutableListOf<ContentBlock>()
                        if (fullThinking.isNotEmpty()) {
                            partialContent.add(ThinkingContent(thinking = fullThinking.toString(), thinkingSignature = thinkingSignature, durationMs = thinkingDuration.takeIf { it > 0 }))
                        }
                        if (fullText.isNotEmpty()) {
                            partialContent.add(TextContent(text = fullText.toString(), durationMs = (System.currentTimeMillis() - textStartTime).takeIf { it > 0 }))
                        }
                        partialContent.add(TextContent("\n\n[输出被内容安全策略截断]"))
                        val partialMessage = AssistantMessage(
                            id = messageId,
                            content = partialContent,
                            stopReason = StopReason.ERROR,
                            usage = Usage()
                        )
                        transcript.add(partialMessage)
                        services.messageListener?.onMessageAdded(listOf(partialMessage))
                        push(MessageEndEvent(messageId, turnId, context.sessionId ?: "default", partialMessage, usage = partialMessage.usage, modelName = context.modelId))
                        logger.info("${logPrefix}[Turn {}] Saved partial message before content-filter abort ({} chars text, {} chars thinking)",
                            turnId, fullText.length, fullThinking.length)
                    }
                    throw ContentFilteredException(
                        "模型输出被内容安全策略拦截，请调整问题后重试", e
                    )
                } else {
                    logger.error("${logPrefix}LLM call failed after {} retries: {}", retryCount, e.message)
                    throw e
                }
            }
        }

        // Build the assistant message
        val finalResponse = lastResponseWithResults
        val responseToolCalls = lastToolCallResponse?.results?.flatMap { it.output.toolCalls }
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
            contentBlocks.add(
                ThinkingContent(
                    thinking = fullThinking.toString(),
                    thinkingSignature = thinkingSignature,
                    durationMs = thinkingDuration.takeIf { it > 0 }
                )
            )
        }
        if (fullText.isNotEmpty()) {
            contentBlocks.add(TextContent(text = fullText.toString(), durationMs = textDuration.takeIf { it > 0 }))
        }
        contentBlocks.addAll(responseToolCalls)

        val finishReasonStr = finalResponse?.results?.firstOrNull()?.metadata?.finishReason
            ?: lastFinishReason
        // Prefer usage from the dedicated usage chunk (message_delta), fall back to
        // lastResponseWithResults for providers that embed usage in a content-bearing chunk.
        // NOTE: ChatResponseMetadata.getUsage() never returns null (defaults to EmptyUsage
        // with all zeros), so we must check for meaningful values — not just non-null —
        // otherwise an earlier empty chunk (e.g. Anthropic's thinking signature chunk)
        // would shadow the real usage carried by the message_delta/tool-call chunk.
        val usage = lastResponseWithUsage?.metadata?.usage?.takeIf { it.promptTokens > 0 || it.completionTokens > 0 }
            ?: finalResponse?.metadata?.usage?.takeIf { it.promptTokens > 0 || it.completionTokens > 0 }

        val rawUsage = usage?.let { u ->
            Usage(
                inputTokens = u.promptTokens,
                outputTokens = u.completionTokens,
                cacheReadTokens = u.cacheReadInputTokens?.toInt() ?: 0,
                cacheWriteTokens = u.cacheWriteInputTokens?.toInt() ?: 0,
                durationMs = streamDuration
            )
        } ?: Usage(durationMs = streamDuration)

        val stopReason = when (finishReasonStr) {
            "length" -> StopReason.LENGTH
            "tool_calls" -> StopReason.TOOL_USE
            // Some OpenAI-compatible providers report "stop" (or no finish reason at
            // all) even when the response contains tool calls; trust the actual
            // content over the provider flag.
            else -> if (responseToolCalls.isNotEmpty()) StopReason.TOOL_USE else StopReason.STOP
        }
        // Both "the provider said stop" and "the provider never sent a finish reason" collapse into
        // STOP, which ends the agent loop. Log the raw flag so a one-turn stop can be told apart
        // from a gateway whose final frame carried no reason at all.
        logger.debug("${logPrefix}[Turn {}] Stop reason resolved: finishReason={} -> stopReason={}, toolCalls={}, textLen={}",
            turnId, finishReasonStr ?: "<none>", stopReason, responseToolCalls.size, fullText.length)
        if (finishReasonStr == "tool_calls" && responseToolCalls.isEmpty()) {
            logger.warn("${logPrefix}[Turn {}] Provider finished with finish_reason=tool_calls but no tool call was parsed; the turn ends without one", turnId)
        }

        return AssistantMessage(
            id = messageId,
            content = contentBlocks,
            stopReason = stopReason,
            usage = rawUsage,
            sampling = lastSampling?.let {
                SamplingTrace(
                    intent = lastSamplingIntent,
                    source = it.source,
                    requestedTemperature = it.temperature,
                    // Read off the options this turn was actually built with: an adapter may have
                    // dropped the value for a reasoning mode, and that difference is the point.
                    sentTemperature = prompt.options?.temperature
                )
            }
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
        val projectedMessages = CommandMessageProjection.project(transformedMessages)
        val llmMessages = services.messageConverter.toLlmMessages(
            projectedMessages, context.userId ?: "system", context.effectiveOwners
        )
        // A repair turn asks for text only: advertising tool schemas there invites another call
        // instead of the JSON the caller is waiting for.
        val toolCallbacks =
            if (repairTurn) emptyList()
            else tools.map { ToolCallback(it.name, it.description, it.inputSchema) }

        // Timing gate for API-level structured output: multi-turn mode defers enforcement
        // until the completion check sets forceStructuredOutput. Whether the model's protocol
        // can actually express the schema (capabilities.structuredOutput, where an undeclared row
        // means it cannot) is decided by the protocol factory inside buildChatOptions.
        val deferredForMultiTurn = context.outputSchemaMultiTurn && !forceStructuredOutput
        val effectiveOutputSchema =
            if (context.outputSchema != null && !deferredForMultiTurn) context.outputSchema else null

        // Sampling is a per-call stance, not a per-connection one: one row backs a human's chat turn
        // and a background risk verdict. The policy picks the number from intent + row + the model's
        // declared sampling capability; an adapter may still drop it for a reasoning mode.
        val modelConfig = context.modelConfig
        val samplingIntent = if (repairTurn) SamplingIntent.DETERMINISTIC else context.samplingIntent
        // Recorded for the trace; `decide` always answers, so a row is the only condition below.
        val sampling = modelConfig?.let { SamplingPolicy.decide(it, samplingIntent) }
        lastSampling = sampling
        lastSamplingIntent = samplingIntent

        val chatOptions = if (modelConfig != null) {
            val sampledConfig = SamplingPolicy.applyTo(modelConfig, samplingIntent)
            // Thinking is a per-turn concern like sampling: the row says whether the model reasons
            // by default, but a background pass (memory consolidation merge) may need it off to avoid
            // paying for reasoning it will not use.
            val effectiveConfig = context.thinkingEnabled?.let { override ->
                val opts = sampledConfig.options ?: return@let sampledConfig
                if (opts.thinking == override) sampledConfig
                else sampledConfig.copy(options = opts.copy(thinking = override))
            } ?: sampledConfig
            services.buildChatOptions(
                config = effectiveConfig,
                toolCallbacks = toolCallbacks,
                outputSchema = effectiveOutputSchema
            )
        } else {
            // No model config: no capabilities to consult, keep the generic enforcement path.
            DefaultChatOptions(
                model = context.modelId.ifEmpty { null },
                toolCallbacks = toolCallbacks,
                outputSchema = effectiveOutputSchema
            )
        }

        // A reasoning mode carries no sampling field, so a run that asked to be pinned is not pinned:
        // Anthropic with thinking on, and OpenAI with an effort set, drop the number where the row's own
        // options are turned into request options. The per-turn trace says `requested=0.1, sent=null`;
        // this is the run-level line that the whole run ran without the reproducibility constraint.
        if (sampling != null && sampling.source == TemperatureSource.PINNED &&
            chatOptions.temperature == null && !pinnedSamplingDroppedLogged
        ) {
            pinnedSamplingDroppedLogged = true
            logger.warn(
                "${logPrefix}Sampling intent {} asked for temperature {} but the {} adapter sends none in " +
                    "this model's reasoning mode; this run is not sampling-constrained",
                samplingIntent, sampling.temperature, context.protocol
            )
        }

        // Memory is NOT injected into the system prompt (keeps the prompt stable for LLM caching).
        // The LLM retrieves relevant memories on demand via memory_search / memory_read tools,
        // guided by the static memory segment appended by PromptTemplateService.

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
            tools = toolsData,
            toolPromptSegments = context.tools
                .sortedBy { it.promptSegmentOrder }
                .mapNotNull { it.systemPromptSegment },
            dynamicSubAgents = context.tools.any { ToolCapability.SPAWNS_SUBAGENTS in it.capabilities },
            skillLoadingAvailable = context.tools.any { ToolCapability.SKILL_LOADING in it.capabilities },
            outputSchema = context.outputSchema,
            outputSchemaMultiTurn = context.outputSchemaMultiTurn,
            inputVariables = context.inputVariables,
            scriptLlmAvailable = context.scriptEnv.isNotEmpty(),
            sessionVariables = context.sessionVariables.getAll()
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

        val allLlmMessages = if (systemPromptText.isNotBlank()) {
            listOf(LlmSystemMessage(systemPromptText)) + llmMessages
        } else {
            llmMessages
        }
        return Prompt(allLlmMessages, chatOptions)
    }

    /**
     * Get memory refs that were accessed by memory_read / memory_search tools
     * during the current turn.
     */
    fun getMemoryRefs(): List<MemoryRef> = context.memoryAccessTracker.getAccessedRefs()
}