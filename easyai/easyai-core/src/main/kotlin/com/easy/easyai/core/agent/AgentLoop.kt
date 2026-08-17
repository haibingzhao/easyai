package com.easy.easyai.core.agent

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentLoop.Companion.MAX_COMPLETION_CHECK_BONUS
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.*
import com.easy.easyai.core.tool.ToolCallResult
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.validation.InputSchemaValidator
import com.easy.easyai.core.validation.OutputSchemaCompletionCheck
import com.easy.easyai.core.validation.ValidationResult
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import tools.jackson.core.type.TypeReference

private val objectMapper = SharedObjectMapper.instance

private fun parseToolArgs(json: String): Map<String, Any?> =
    objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {})

/**
 * Agent loop runtime configuration.
 *
 * Contains only loop runtime controls (callbacks / abort signal).
 * Identity and behavior config (model, maxIterations, maxRetries, sessionId, options, etc.)
 * are provided by [AgentContext].
 * Hooks (beforeToolCall/afterToolCall) and listeners (messageListener) are provided by [AgentService].
 * Context transformation is provided by [AgentService.transformContextService].
 */
data class AgentLoopConfig(
    val getSteeringMessages: () -> List<EasyAiMessage> = { emptyList() },
    val getFollowUpMessages: () -> List<EasyAiMessage> = { emptyList() },
    val isAbortRequested: () -> Boolean = { false }
)

internal class AgentLoop(
    private val context: AgentContext,
    private val config: AgentLoopConfig,
    private val services: AgentService,
    private val tools: List<ToolDefinition>,
    private val chatModel: ChatModel
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val logPrefix = agentLogPrefix(context.parentAgentId)

    private val pendingToolCallExecutor = PendingToolCallExecutor(
        toolExecutor = services.toolExecutor,
        tools = tools,
        messageListener = services.messageListener,
        agentContext = context,
        eventListeners = services.eventListeners
    )

    /**
     * Append a message to the transcript, notify the message listener,
     * and push a [UserMessageAddedEvent] SSE event if the message is a [UserMessage].
     */
    private suspend fun ProducerScope<AgentEvent, List<AssistantMessage>>.appendAndNotify(
        message: EasyAiMessage,
        transcript: MutableList<EasyAiMessage>
    ) {
        transcript.add(message)
        services.messageListener?.onMessageAdded(listOf(message))
        if (message is UserMessage) {
            val text = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
            push(UserMessageAddedEvent(messageId = message.id, content = text, sessionId = context.sessionId ?: "default", metadata = message.metadata))
        }
    }

    private val loopRunner = AgentLoopRunner(context, chatModel, services)

    /**
     * Bonus iteration budget: allows completion checks to grant extra iterations
     * beyond [AgentContext.maxIterations]. Each grant decrements the budget;
     * once exhausted, completion checks can no longer extend the loop.
     * Initialized to [MAX_COMPLETION_CHECK_BONUS].
     */
    private var completionCheckBonusBudget = MAX_COMPLETION_CHECK_BONUS
    private var completionCheckBonusPending = false

    /** Why the loop ended — set during run() before end() is called. */
    @Volatile
    var endReason: String = "normal"
        private set

    fun run(
        transcript: MutableList<EasyAiMessage>
    ): EventStream<AgentEvent, List<AssistantMessage>> {
        return EventStream.create {

            // Validate input schema before entering the agent loop.
            // Only validate for top-level agents (not sub-agents called via SubAgentTool).
            // Sub-agents receive their input via the LLM's prompt parameter, not structured inputData.
            val inputSchema = context.inputSchema
            if (inputSchema != null && context.parentAgentId == null) {
                val inputVariables = context.inputVariables
                // Only validate when inputData was explicitly provided.
                // Follow-up messages without inputData skip validation.
                if (inputVariables.isNotEmpty()) {
                    val validator = InputSchemaValidator()
                    val validationResult = validator.validateInput(inputSchema, inputVariables)
                    if (validationResult is ValidationResult.Invalid) {
                        val errorMsg = "Input schema validation failed:\n${validationResult.errors.joinToString("\n")}"
                        val errorMessage = AssistantMessage(
                            content = listOf(TextContent(errorMsg)),
                            stopReason = StopReason.STOP,
                            usage = Usage()
                        )
                        transcript.add(errorMessage)
                        services.messageListener?.onMessageAdded(listOf(errorMessage))
                        push(TurnStartEvent(0, context.sessionId ?: "default"))
                        push(MessageStartEvent(errorMessage.id, 0, context.sessionId ?: "default"))
                        push(MessageEndEvent(errorMessage.id, 0, context.sessionId ?: "default", errorMessage, usage = errorMessage.usage, modelName = context.modelId))
                        push(TurnEndEvent(0, context.sessionId ?: "default"))
                        endReason = "input_schema_validation_failed"
                        end(listOf(errorMessage))
                        return@create
                    }
                }
            }

            // Detect and execute pending toolCalls (resume scenario)
            pendingToolCallExecutor.executePendingToolCallsIfNeeded(transcript, this)

            // Clear stale completion-check state from a previous abnormal termination
            context.sessionId?.let { sid ->
                services.completionChecks.filterIsInstance<OutputSchemaCompletionCheck>()
                    .forEach { it.resetSession(sid) }
            }

            var turnId = 0
            var continueLoop = true

            try {
                while (continueLoop) {
                    // Check abort at the start of each iteration to avoid starting a new LLM call after cancel
                    if (config.isAbortRequested()) {
                        logger.info("${logPrefix}Abort requested at turn {}, stopping agent loop", turnId)
                        break
                    }
                    continueLoop = runInnerLoop(transcript, turnId)
                    turnId++
                }

                // Determine end reason:
                // 1. max_iterations — loop exhausted its iteration budget (runInnerLoop returned false
                //    because turnId >= maxIterations inside runInnerLoop, NOT because of abort/pause)
                // 2. cancelled — abort was requested (either caught at while-top via break, or inside
                //    runInnerLoop which returned false; in both cases isAbortRequested() is true)
                // 3. normal — any other exit (LLM finished, needPause, etc.)
                endReason = when {
                    turnId >= context.maxIterations && !config.isAbortRequested() -> {
                        logger.warn("${logPrefix}Agent loop ended: max iterations ({}) reached at turn {}", context.maxIterations, turnId)
                        "max_iterations"
                    }
                    config.isAbortRequested() -> {
                        logger.info("${logPrefix}Agent loop ended: abort requested at turn {}", turnId)
                        "cancelled"
                    }
                    else -> "normal"
                }
            } catch (_: CancellationException) {
                // Graceful shutdown: SSE client disconnected or coroutine cancelled.
                // Don't re-throw — complete the EventStream normally so downstream
                // sends a proper Done event with accumulated assistant messages.
                logger.info("${logPrefix}Agent loop cancelled at turn {}, ending gracefully", turnId)
                endReason = "cancelled"
            }

            end(transcript.filterIsInstance<AssistantMessage>())
        }
    }

    private suspend fun ProducerScope<AgentEvent, List<AssistantMessage>>.runInnerLoop(
        transcript: MutableList<EasyAiMessage>,
        turnId: Int
    ): Boolean {
        logger.debug("${logPrefix}[Turn {}] runInnerLoop started, transcript size={}, maxIterations={}", turnId, transcript.size, context.maxIterations)
        if (turnId >= context.maxIterations) {
            // Completion check may grant bonus iterations beyond maxIterations (budget-capped)
            if (completionCheckBonusPending) {
                completionCheckBonusPending = false
                logger.info("${logPrefix}[Turn {}] Using completion-check bonus iteration (budget left={})", turnId, completionCheckBonusBudget)
            } else {
                return false
            }
        } else {
            // Within normal limit: discard any stale pending bonus (it was for a turn that didn't need it)
            completionCheckBonusPending = false
        }

        // Clear memory access tracker for this turn
        context.memoryAccessTracker.clear()

        push(TurnStartEvent(turnId, context.sessionId ?: "default"))

        logger.debug("${logPrefix}[Turn {}] Transforming context ({} messages)", turnId, transcript.size)
        val messageTimestamps = services.getMessageTimestamps()
        val transformedMessages = transformContext(transcript, turnId, messageTimestamps, CompactionTriggerType.Auto)

        // Prepare the prompt with system prompt and tool callbacks
        val prompt = loopRunner.preparePrompt(transformedMessages, tools)

        val messageId = generateMessageId()
        push(MessageStartEvent(messageId, turnId, context.sessionId ?: "default"))

        // Call LLM and build assistant message, with overflow retry handling
        val rawAssistantMessage = callLLMWithOverflowHandling(
            transcript = transcript,
            prompt = prompt,
            messageId = messageId,
            turnId = turnId,
            messageTimestamps = messageTimestamps
        )

        // Attach context references (rules only at this point; memory refs added after tool execution)
        var assistantMessage = loopRunner.getLastReferences()?.let { refs ->
            rawAssistantMessage.copy(references = refs)
        } ?: rawAssistantMessage

        // Add to transcript and persist immediately — ensures assistant message is saved
        // even if tool execution below throws an unexpected exception (OOM, cancellation, etc.)
        val assistantTranscriptIndex = transcript.size
        transcript.add(assistantMessage)
        services.messageListener?.onMessageAdded(listOf(assistantMessage))

        val toolCalls = assistantMessage.toolCalls()
        logger.debug("${logPrefix}[Turn {}] Assistant message built: stopReason={}, contentBlocks={}, toolCalls={}, usage=({}/{}/{}/{})",
            turnId, assistantMessage.stopReason, assistantMessage.content.size, toolCalls.size,
            assistantMessage.usage.inputTokens, assistantMessage.usage.outputTokens, assistantMessage.usage.cacheReadTokens, assistantMessage.usage.cacheWriteTokens)

        var waitForUserReason: String? = null

        if (toolCalls.isNotEmpty()) {
            // Check abort BEFORE executing tools to avoid starting expensive operations
            if (config.isAbortRequested()) {
                logger.info("${logPrefix}[Turn {}] Abort requested before tool execution, skipping {} tool calls", turnId, toolCalls.size)
                // Mark assistant message as aborted so ChatSession.resume() can detect interrupted context
                assistantMessage = assistantMessage.copy(stopReason = StopReason.ABORTED)
                transcript[assistantTranscriptIndex] = assistantMessage
                services.messageListener?.onMessageUpdated(assistantMessage.id, assistantMessage, setOf(MessageUpdateField.STOP_REASON))
                push(MessageEndEvent(messageId, turnId, context.sessionId ?: "default", assistantMessage, usage = assistantMessage.usage, modelName = context.modelId))
                push(TurnEndEvent(turnId, context.sessionId ?: "default"))
                return false
            }

            logger.debug("${logPrefix}[Turn {}] Executing {} tool calls: {}", turnId, toolCalls.size,
                toolCalls.joinToString(", ") { "${it.name}(${it.id})" })
            val toolExecStartTime = System.currentTimeMillis()
            val toolResults = executeToolCallsWithHooks(
                messageId = messageId,
                toolCalls = toolCalls,
                tools = tools,
                eventStream = this,
                turnId = turnId
            )
            val toolExecDuration = System.currentTimeMillis() - toolExecStartTime
            logger.debug("${logPrefix}[Turn {}] Tool execution completed in {}ms, {} results returned", turnId, toolExecDuration, toolResults.size)

            // Merge memory access refs into assistant message before finalizing
            val memoryRefs = loopRunner.getMemoryRefs()
            if (memoryRefs.isNotEmpty()) {
                val existingRefs = assistantMessage.references
                val mergedRefs = ContextReferences(
                    memories = memoryRefs,
                    rules = existingRefs?.rules ?: emptyList()
                )
                assistantMessage = assistantMessage.copy(references = mergedRefs)
                // Update transcript entry and persist only the metadata (lightweight)
                transcript[assistantTranscriptIndex] = assistantMessage
                services.messageListener?.onMessageUpdated(assistantMessage.id, assistantMessage, setOf(MessageUpdateField.METADATA))
            }

            // Finalize: push event (message already persisted at transcript-add time)
            push(MessageEndEvent(messageId, turnId, context.sessionId ?: "default", assistantMessage, usage = assistantMessage.usage, modelName = context.modelId))

            // Build ToolResultMessage from tool results for next LLM iteration
            // Filter out WaitForUserContent results - they represent pending user questions, not tool results
            val toolCallMap = toolCalls.associateBy { it.id }
            val toolResultEntries = mutableListOf<ToolResultEntry>()
            for (r in toolResults) {
                val tc = toolCallMap[r.toolCallId]
                if (tc == null) {
                    logger.warn("${logPrefix}ToolCallResult for {} has no matching ToolCallContent, skipping", r.toolCallId)
                    continue
                } else if (r.needPause) {
                    // Skip WaitForUserContent results - don't add to transcript or ToolResultMessage
                    logger.debug("${logPrefix}Skipping needPause tool result for {} ({})", r.toolCallId, tc.name)
                    waitForUserReason = r.pauseReason ?: "ask_question"
                    continue
                } else {
                    // Engine already applied ToolResultGuard via guardResult(); resultText is within limits.
                    val entry = ToolResultEntry(
                        toolCallId = r.toolCallId,
                        toolName = tc.name,
                        result = r.resultText,
                        durationMs = r.durationMs,
                        isError = r.isError,
                        exitCode = r.exitCode,
                        mimeType = r.mimeType,
                        isSkipped = r.isSkipped,
                        usage = r.usage
                    )
                    toolResultEntries.add(entry)
                }
            }
            val toolResultMessage = ToolResultMessage(toolResults = toolResultEntries)
            transcript.add(toolResultMessage)
            // ToolResultMessage is persisted to Message table
            services.messageListener?.onMessageAdded(listOf(toolResultMessage))
            // Check if abort was requested during tool execution
            if (config.isAbortRequested()) {
                logger.info("${logPrefix}[Turn {}] Abort requested during tool execution, stopping loop", turnId)
                // Mark assistant message as aborted so ChatSession.resume() can detect interrupted context
                assistantMessage = assistantMessage.copy(stopReason = StopReason.ABORTED)
                transcript[assistantTranscriptIndex] = assistantMessage
                services.messageListener?.onMessageUpdated(assistantMessage.id, assistantMessage, setOf(MessageUpdateField.STOP_REASON))
                push(TurnEndEvent(turnId, context.sessionId ?: "default"))
                return false
            }
        } else {
            // No tool calls — finalize assistant message (already persisted above)
            push(MessageEndEvent(messageId, turnId, context.sessionId ?: "default", assistantMessage, usage = assistantMessage.usage, modelName = context.modelId))
        }

        // If WaitForUserContent was detected, break the loop and end SSE stream
        if (waitForUserReason != null) {
            logger.info("${logPrefix}[Turn {}] NeedPause detected (reason={}), breaking loop to wait for user response", turnId, waitForUserReason)
            // Notify listener (e.g., goal timer) that we're pausing for user input
            services.waitForUserListener?.let { listener ->
                try {
                    listener.onWaitForUser(context.sessionId ?: "default", context.userId ?: "system", waitForUserReason)
                } catch (e: Exception) {
                    logger.warn("${logPrefix}WaitForUserListener failed: {}", e.message)
                }
            }
            push(TurnEndEvent(turnId, context.sessionId ?: "default"))
            return false
        }

        logger.debug("${logPrefix}[Turn {}] Checking steering messages", turnId)
        val steeringMessages = config.getSteeringMessages()
        for (msg in steeringMessages) {
            val taggedMsg = when (msg) {
                is UserMessage -> msg.copy(metadata = msg.metadata + (UserMessage.SOURCE_KEY to UserMessage.SOURCE_STEERING))
                else -> msg
            }
            appendAndNotify(taggedMsg, transcript)
        }

        push(TurnEndEvent(turnId, context.sessionId ?: "default"))

        var continueLoop = when {
            toolCalls.isNotEmpty() -> true
            steeringMessages.isNotEmpty() -> true
            else -> {
                val followUpMessages = config.getFollowUpMessages()
                if (followUpMessages.isNotEmpty()) {
                    for (msg in followUpMessages) {
                        val taggedMsg = when (msg) {
                            is UserMessage -> msg.copy(metadata = msg.metadata + (UserMessage.SOURCE_KEY to UserMessage.SOURCE_FOLLOW_UP))
                            else -> msg
                        }
                        appendAndNotify(taggedMsg, transcript)
                    }
                    true
                } else {
                    false
                }
            }
        }

        // When original logic says "stop", run completion checks
        if (!continueLoop && services.completionChecks.isNotEmpty()) {
            val checkInput = CompletionCheckInput(
                agentContext = context,
                transcript = transcript.toList(),
                turnId = turnId
            )
            for (check in services.completionChecks) {
                val result = try {
                    check.check(checkInput)
                } catch (e: Exception) {
                    logger.warn("${logPrefix}[Turn {}] Completion check {} failed: {}", turnId, check::class.simpleName, e.message)
                    CompletionCheckResult.Done
                }
                if (result is CompletionCheckResult.Continue) {
                    logger.info("${logPrefix}[Turn {}] Completion check {} requested continuation", turnId, check::class.simpleName)
                    // Multi-turn: only enable API-level structured output when OutputSchemaCompletionCheck triggers
                    if (check is OutputSchemaCompletionCheck && context.outputSchemaMultiTurn && context.outputSchema != null) {
                        loopRunner.enableForcedStructuredOutput()
                    }
                    // Grant bonus only if the next iteration will hit maxIterations
                    if (turnId + 1 >= context.maxIterations) {
                        if (completionCheckBonusBudget > 0) {
                            completionCheckBonusBudget--
                            completionCheckBonusPending = true
                        } else {
                            // Budget exhausted and next turn is past limit → cannot continue
                            logger.info("${logPrefix}[Turn {}] Completion-check bonus budget exhausted, stopping", turnId)
                            break
                        }
                    }
                    continueLoop = true
                    // Inject prompt as UserMessage with metadata for frontend
                    result.prompt?.let { promptText ->
                        val msg = UserMessage(
                            id = generateMessageId(),
                            content = listOf(TextContent(promptText)),
                            metadata = mapOf(UserMessage.SOURCE_KEY to UserMessage.SOURCE_COMPLETION_CHECK)
                        )
                        appendAndNotify(msg, transcript)
                    }
                    break  // Any one check says continue → continue
                }
            }
        }

        // Final re-check: a steering/followUp message may have arrived while
        // completion checks were running (or during the LLM response processing).
        // Without this, the message would sit in the queue forever because the
        // outer while-loop would have already exited.
        if (!continueLoop) {
            val lateSteering = config.getSteeringMessages()
            if (lateSteering.isNotEmpty()) {
                logger.info("${logPrefix}[Turn {}] Late-arriving steering message(s) picked up after completion checks", turnId)
                for (msg in lateSteering) {
                    val taggedMsg = when (msg) {
                        is UserMessage -> msg.copy(metadata = msg.metadata + (UserMessage.SOURCE_KEY to UserMessage.SOURCE_STEERING))
                        else -> msg
                    }
                    appendAndNotify(taggedMsg, transcript)
                }
                continueLoop = true
            } else {
                val lateFollowUp = config.getFollowUpMessages()
                if (lateFollowUp.isNotEmpty()) {
                    logger.info("${logPrefix}[Turn {}] Late-arriving followUp message(s) picked up after completion checks", turnId)
                    for (msg in lateFollowUp) {
                        val taggedMsg = when (msg) {
                            is UserMessage -> msg.copy(metadata = msg.metadata + (UserMessage.SOURCE_KEY to UserMessage.SOURCE_FOLLOW_UP))
                            else -> msg
                        }
                        appendAndNotify(taggedMsg, transcript)
                    }
                    continueLoop = true
                }
            }
        }

        logger.debug("${logPrefix}[Turn {}] runInnerLoop finished, continueLoop={}, transcriptSize={}", turnId, continueLoop, transcript.size)
        return continueLoop
    }

    /**
     * Execute tool calls with beforeToolCall and afterToolCall hooks.
     * Uses a 3-phase approach to enable parallel execution:
     *   Phase 1: Run all beforeToolCall hooks (permission checks)
     *   Phase 2: Batch-execute allowed toolCalls via engine (supports parallel)
     *   Phase 3: Run afterToolCall hooks on each result
     */
    private suspend fun ProducerScope<AgentEvent, List<AssistantMessage>>.executeToolCallsWithHooks(
        messageId: String,
        toolCalls: List<ToolCallContent>,
        tools: List<ToolDefinition>,
        eventStream: ProducerScope<AgentEvent, List<AssistantMessage>>,
        turnId: Int
    ): List<ToolCallResult> {
        // Phase 1: Run beforeToolCall hooks for all toolCalls
        val executableToolCalls = mutableListOf<ToolCallContent>()
        val results = mutableListOf<ToolCallResult>()

        for (toolCall in toolCalls) {
            // Parse arguments with graceful error handling: LLM may produce malformed JSON.
            // If parsing fails, skip the beforeToolCall hook and record as failed.
            val parsedArgs: Map<String, Any?>
            try {
                parsedArgs = parseToolArgs(toolCall.arguments)
            } catch (e: Exception) {
                logger.warn("${logPrefix}Tool call {} ({}) has invalid JSON arguments, skipping hook evaluation: {}",
                    toolCall.name, toolCall.id, e.message)
                results.add(ToolCallResult(
                    toolCallId = toolCall.id,
                    resultText = "Invalid JSON arguments for tool '${toolCall.name}': ${e.message}",
                    isError = true,
                    durationMs = 0
                ))
                continue
            }

            val beforeResult = services.beforeToolCall(BeforeToolCallContext(
                toolCallId = toolCall.id,
                toolName = toolCall.name,
                arguments = parsedArgs,
                projectId = context.projectId,
                projectPath = context.projectPath,
                parentAgentId = context.parentAgentId
            ))
            when (beforeResult) {
                is BeforeToolCallResult.Block -> {
                    logger.warn("${logPrefix}Tool call {} ({}) blocked: {}", toolCall.name, toolCall.id, beforeResult.reason)
                    results.add(ToolCallResult(
                        toolCallId = toolCall.id,
                        resultText = "Blocked: ${beforeResult.reason}",
                        isError = false,
                        durationMs = 0
                    ))
                }
                is BeforeToolCallResult.PermissionRequest -> {
                    logger.info("${logPrefix}Tool call {} ({}) requires permission: {} {}",
                        toolCall.name, toolCall.id, beforeResult.permission, beforeResult.pattern)
                    push(PermissionRequestEvent(
                        toolCallId = beforeResult.toolCallId,
                        toolName = beforeResult.toolName,
                        permission = beforeResult.permission,
                        pattern = beforeResult.pattern,
                        arguments = beforeResult.arguments,
                        sessionId = context.sessionId ?: "default"
                    ))
                    results.add(ToolCallResult(
                        toolCallId = toolCall.id,
                        resultText = "Waiting for permission...",
                        isError = false,
                        durationMs = 0,
                        needPause = true,
                        pauseReason = "permission_request"
                    ))
                    // Mark remaining unexecuted toolCalls as skipped
                    val currentIndex = toolCalls.indexOfFirst { it.id == toolCall.id }
                    for (i in (currentIndex + 1) until toolCalls.size) {
                        val skipped = toolCalls[i]
                        results.add(ToolCallResult(
                            toolCallId = skipped.id,
                            resultText = "Skipped: waiting for permission on ${toolCall.name}",
                            isError = false,
                            durationMs = 0,
                            isSkipped = true
                        ))
                    }
                    return results
                }
                is BeforeToolCallResult.Allow -> {
                    executableToolCalls.add(toolCall)
                }
            }
        }

        // Phase 2: Batch-execute all allowed toolCalls via engine
        // Engine dispatches PARALLEL tools concurrently, SEQUENTIAL tools serially.
        if (executableToolCalls.isNotEmpty()) {
            // Synchronous pre-execution hook: ensures listeners (e.g., SnapshotEventListener)
            // complete their work (e.g., commitAs USER) BEFORE tools modify the working tree.
            services.eventListeners.forEach { listener ->
                try {
                    listener.beforeToolExecutionBatch(context, executableToolCalls.map { it.id })
                } catch (e: Exception) {
                    logger.warn("${logPrefix}Listener {} beforeToolExecutionBatch failed: {}",
                        listener::class.simpleName, e.message)
                }
            }

            val engineResults = services.toolExecutor.executeToolCalls(
                agentContext = context,
                toolCalls = executableToolCalls,
                tools = tools,
                eventStream = eventStream,
                scope = this,
                turnId = turnId,
                messageId = messageId
            )
            results.addAll(engineResults)

            // Synchronous post-execution hook: ensures listeners (e.g., SnapshotEventListener)
            // complete their work (e.g., commitAs LLM_AGENT, push checkpoint) after tools finish
            // but before the next agent turn begins.
            val fileChangingToolNames = tools.filter { it.tracksFileChanges }.map { it.name }.toSet()
            val hasFileChangingTools = executableToolCalls.any { it.name in fileChangingToolNames }
            services.eventListeners.forEach { listener ->
                try {
                    listener.afterToolExecutionBatch(
                        context,
                        executableToolCalls.map { it.id },
                        messageId,
                        ::push,
                        hasFileChangingTools
                    )
                } catch (e: Exception) {
                    logger.warn("${logPrefix}Listener {} afterToolExecutionBatch failed: {}",
                        listener::class.simpleName, e.message)
                }
            }
        }

        // Phase 3: Run afterToolCall hooks — only on actually executed tools
        // Note: `terminate` now only skips remaining hooks, not tool execution itself
        // (all tools were already batch-executed in Phase 2)
        val executedToolCallIds = executableToolCalls.map { it.id }.toSet()
        for (toolResult in results) {
            if (toolResult.toolCallId !in executedToolCallIds) continue
            val tc = toolCalls.find { it.id == toolResult.toolCallId } ?: continue
            val afterResult = services.afterToolCall(AfterToolCallContext(
                toolCallId = tc.id,
                toolName = tc.name,
                result = ToolResult(
                    content = listOf(TextContent(toolResult.resultText)),
                    isError = toolResult.isError
                )
            ))
            if (afterResult.terminate) {
                logger.info("${logPrefix}Tool call {} ({}) requested termination", tc.name, tc.id)
                break
            }
        }

        return results
    }

    /**
     * Transform context with the specified trigger type.
     * Updates the transcript if compaction occurred.
     */
    private suspend fun ProducerScope<AgentEvent, List<AssistantMessage>>.transformContext(
        transcript: MutableList<EasyAiMessage>,
        turnId: Int,
        messageTimestamps: Map<String, Long>,
        triggerType: CompactionTriggerType
    ): List<EasyAiMessage> {
        val transformInput = TransformContextInput(
            agentContext = context,
            messages = transcript.toList(),
            turnId = turnId,
            modelContextLength = context.modelContextLength,
            compactionTriggerType = triggerType,
            messageTimestamps = messageTimestamps,
            eventPusher = { event -> push(event) },
            chatModel = chatModel
        )
        val transformedMessages = services.transformContextService.transform(transformInput)

        // If compaction occurred, update transcript so subsequent iterations
        // work with the compacted messages instead of re-compacting from scratch
        if (transformedMessages.size < transcript.size) {
            logger.info("${logPrefix}[Turn {}] Context compacted ({}): {} -> {} messages, updating transcript",
                turnId, triggerType::class.simpleName, transcript.size, transformedMessages.size)
            transcript.clear()
            transcript.addAll(transformedMessages)
        }

        return transformedMessages
    }

    /**
     * Check if the exception indicates a context overflow error from the LLM.
     * Delegates to [LlmErrorClassifier] which uses Spring AI's exception hierarchy
     * for more robust detection.
     */
    private fun isContextOverflowError(e: Exception): Boolean {
        return LlmErrorClassifier.isContextOverflow(e)
    }

    /**
     * Call LLM with overflow error handling.
     * If a context overflow error is detected, re-transform context with Overflow trigger
     * and retry the LLM call.
     */
    private suspend fun ProducerScope<AgentEvent, List<AssistantMessage>>.callLLMWithOverflowHandling(
        transcript: MutableList<EasyAiMessage>,
        prompt: Prompt,
        messageId: String,
        turnId: Int,
        messageTimestamps: Map<String, Long>
    ): AssistantMessage {
        try {
            return loopRunner.callLLMAndBuildResponse(
                transcript = transcript,
                prompt = prompt,
                messageId = messageId,
                turnId = turnId,
                push = { event -> push(event) }
            )
        } catch (e: Exception) {
            if (e is CancellationException) {
                throw e
            }
            if (isContextOverflowError(e)) {
                logger.warn("${logPrefix}[Turn {}] Context overflow detected, re-compacting with Overflow trigger: {}",
                    turnId, e.message)

                // Re-transform context with Overflow trigger (bypasses check interval)
                val compactedMessages = transformContext(
                    transcript = transcript,
                    turnId = turnId,
                    messageTimestamps = messageTimestamps,
                    triggerType = CompactionTriggerType.Overflow(context.modelId)
                )

                // Prepare new prompt with compacted messages
                val newPrompt = loopRunner.preparePrompt(compactedMessages, tools)

                // Retry LLM call with compacted context
                return loopRunner.callLLMAndBuildResponse(
                    transcript = transcript,
                    prompt = newPrompt,
                    messageId = messageId,
                    turnId = turnId,
                    push = { event -> push(event) }
                )
            }
            throw e
        }
    }

    companion object {
        /**
         * Maximum number of bonus iterations a completion check can grant beyond maxIterations.
         * Prevents infinite loop extension while allowing nudges to reach the LLM.
         */
        private const val MAX_COMPLETION_CHECK_BONUS = 1
    }
}