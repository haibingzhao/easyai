package com.easy.easyai.web.handler

import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.*
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.web.model.ChatStreamEvent

/**
 * Converts a [CustomEvent] to [ChatStreamEvent]s based on customType.
 * Implementations are registered per customType and passed to [ChatEventConverter.convert].
 */
interface CustomEventConverter {
    val customType: String
    fun convert(event: CustomEvent): List<ChatStreamEvent>
}

/**
 * Converts EasyAI AgentEvent to ChatStreamEvent for SSE streaming.
 */
object ChatEventConverter {

    /**
     * Convert a single EasyAI event to one or more ChatStreamEvents.
     * Returns a list because one EasyAI event may map to multiple ChatStreamEvents.
     *
     * @param event The agent event to convert
     * @param customConverters Optional list of converters for [CustomEvent] dispatch by customType
     */
    fun convert(event: AgentEvent, customConverters: List<CustomEventConverter> = emptyList()): List<ChatStreamEvent> = when (event) {
        is AgentStartEvent -> listOf(ChatStreamEvent.Start(event.sessionId))
        is AgentEndEvent -> {
            if (event.reason == "aborted") {
                listOf(ChatStreamEvent.Cancelled(reason = "user_cancelled"))
            } else {
                emptyList() // handled by Done event
            }
        }
        is TurnStartEvent -> emptyList() // not exposed to a client
        is TurnEndEvent -> emptyList() // not exposed to a client
        is MessageStartEvent -> emptyList() // implied by first delta
        is MessageUpdateEvent -> listOf(
            ChatStreamEvent.TextDelta(contentIndex = 0, delta = event.delta, turnId = event.turnId,
                messageId = event.messageId, subAgentToolCallId = event.subAgentToolCallId, subAgentName = event.subAgentName)
        )
        is ThinkingUpdateEvent -> listOf(
            ChatStreamEvent.ThinkingDelta(contentIndex = 0, delta = event.delta, turnId = event.turnId,
                messageId = event.messageId, subAgentToolCallId = event.subAgentToolCallId, subAgentName = event.subAgentName)
        )
        is ThinkingEndEvent -> listOf(
            ChatStreamEvent.ThinkingEnd(contentIndex = 0, turnId = event.turnId, durationMs = event.durationMs.takeIf { it > 0 },
                subAgentToolCallId = event.subAgentToolCallId, subAgentName = event.subAgentName)
        )
        is MessageEndEvent -> convertMessageEnd(event)
        is ToolExecutionStartEvent -> listOf(
            ChatStreamEvent.ToolExecutionStart(
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                args = event.args,
                turnId = event.turnId,
                subAgentToolCallId = event.subAgentToolCallId,
                subAgentName = event.subAgentName,
                tracksFileChanges = event.tracksFileChanges
            )
        )
        is ToolExecutionUpdateEvent -> listOf(
            ChatStreamEvent.ToolExecutionUpdate(
                toolCallId = event.toolCallId,
                output = when (val update = event.update) {
                    is ToolUpdate.PartialContent -> update.text
                    is ToolUpdate.Progress -> update.message
                    is ToolUpdate.PartialResult -> update.data.toString()
                    is ToolUpdate.SubAgentEvent -> "" // Sub-agent events are forwarded directly, not as tool output
                },
                turnId = event.turnId,
                subAgentToolCallId = event.subAgentToolCallId,
                subAgentName = event.subAgentName
            )
        )
        is ToolCallStatusUpdateEvent -> listOf(
            ChatStreamEvent.ToolCallStatus(
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                status = event.status.name,
                turnId = event.turnId,
                subAgentToolCallId = event.subAgentToolCallId,
                subAgentName = event.subAgentName
            )
        )
        is ToolExecutionEndEvent -> {
            val toolResultBlock = event.result.content.filterIsInstance<ToolResultContent>().firstOrNull()
            // Fallback: tools may return errors as TextContent (e.g. errorResult() helper)
            val resultText = toolResultBlock?.output
                ?: event.result.content.filterIsInstance<TextContent>()
                    .joinToString("\n") { it.text }.takeIf { it.isNotEmpty() }
            listOf(
                ChatStreamEvent.ToolExecutionEnd(
                    toolCallId = event.toolCallId,
                    toolName = event.toolName,
                    result = resultText,
                    isError = toolResultBlock?.isError ?: event.isError,
                    exitCode = toolResultBlock?.exitCode,
                    mimeType = toolResultBlock?.mimeType,
                    truncated = toolResultBlock?.truncated == true,
                    turnId = event.turnId,
                    subAgentToolCallId = event.subAgentToolCallId,
                    subAgentName = event.subAgentName,
                    toolUsage = event.usage?.toUsageInfo(),
                    tracksFileChanges = event.tracksFileChanges
                )
            )
        }
        is ErrorEvent -> listOf(
            ChatStreamEvent.Error(
                reason = "error",
                errorMessage = event.error.message,
                isRetryable = event.isRetryable,
                messageId = event.messageId,
                turnId = event.turnId.takeIf { it >= 0 }
            )
        )
        is RetryEvent -> listOf(
            ChatStreamEvent.Retry(
                attempt = event.attempt,
                maxRetries = event.maxRetries,
                backoffMs = event.backoffMs,
                turnId = event.turnId
            )
        )
        is CompactionStartEvent -> listOf(
            ChatStreamEvent.CompactionStart(
                turnId = event.turnId,
                reason = event.reason,
                messageCount = event.messageCount
            )
        )
        is CompactionEndEvent -> listOf(
            ChatStreamEvent.CompactionEnd(
                turnId = event.turnId,
                summary = event.summary,
                compactedCount = event.compactedCount,
                tokensSaved = event.tokensSaved,
                tailStartMessageId = event.tailStartMessageId,
                currentTokens = event.currentTokens,
                durationMs = event.durationMs,
                usage = event.usage.toUsageInfo(includeDuration = false),
                variables = event.variables.ifEmpty { null }
            )
        )
        is PermissionRequestEvent -> listOf(
            ChatStreamEvent.PermissionRequest(
                toolCallId = event.toolCallId,
                toolName = event.toolName,
                permission = event.permission,
                pattern = event.pattern,
                arguments = event.arguments,
                subAgentToolCallId = event.subAgentToolCallId,
                subAgentName = event.subAgentName
            )
        )
        is UserMessageAddedEvent -> listOf(
            ChatStreamEvent.UserMessageAdded(
                messageId = event.messageId,
                content = event.content,
                metadata = event.metadata
            )
        )
        is CustomEvent -> {
            val converter = customConverters.firstOrNull { it.customType == event.customType }
            converter?.convert(event) ?: emptyList()
        }
    }

    private fun convertMessageEnd(event: MessageEndEvent): List<ChatStreamEvent> {
        val result = mutableListOf<ChatStreamEvent>()
        val message = event.message
        var contentIndex = 0

        for (block in message.content) {
            when (block) {
                is TextContent -> {
                    result.add(ChatStreamEvent.TextEnd(
                        contentIndex = contentIndex,
                        turnId = event.turnId,
                        durationMs = block.durationMs?.takeIf { it > 0 },
                        subAgentToolCallId = event.subAgentToolCallId,
                        subAgentName = event.subAgentName
                    ))
                }
                is ThinkingContent -> {
                    // ThinkingEnd is handled by ThinkingEndEvent during streaming.
                    // For persistence/historical messages, durationMs is on the ThinkingContent block.
                }
                is ToolCallContent -> {
                    // Emit toolcall_start + toolcall_end so all tool cards appear immediately on the frontend
                    result.add(ChatStreamEvent.ToolCallStart(
                        contentIndex = contentIndex,
                        id = block.id,
                        toolName = block.name,
                        subAgentToolCallId = event.subAgentToolCallId,
                        subAgentName = event.subAgentName
                    ))
                    result.add(ChatStreamEvent.ToolCallEnd(
                        contentIndex = contentIndex,
                        id = block.id,
                        subAgentToolCallId = event.subAgentToolCallId,
                        subAgentName = event.subAgentName
                    ))
                }
                is ImageContent -> {
                    // Images aren't streamed via SSE
                }
                is ToolResultContent -> {
                    // Tool results are handled separately by tool execution events
                }
                is CustomContent -> {
                    // Custom content blocks are not streamed via SSE; they are loaded from persistence
                }
                is FileRefContent -> {
                    // File references aren't streamed via SSE; they are stored as metadata
                }
            }
            contentIndex++
        }

        // Emit MessageEnd event with usage info (only if usage has meaningful values)
        val usageInfo = event.usage?.toUsageInfo()
        // Convert context references from domain model to SSE DTO
        val refsSnapshot = message.references?.let { ChatStreamEvent.ReferencesSnapshot.from(it) }
        result.add(ChatStreamEvent.MessageEnd(
            messageId = event.messageId,
            turnId = event.turnId,
            usage = usageInfo,
            subAgentToolCallId = event.subAgentToolCallId,
            subAgentName = event.subAgentName,
            references = refsSnapshot
        ))

        return result
    }

    /**
     * Create Done event from final assistant messages.
     */
    fun createDoneEvent(
        messages: List<AssistantMessage>,
        reason: String = "stop",
        endReason: String = "normal"
    ): ChatStreamEvent {
        val totalUsage = messages.fold(ChatStreamEvent.UsageInfo()) { acc, msg ->
            ChatStreamEvent.UsageInfo(
                inputTokens = acc.inputTokens + msg.usage.inputTokens,
                outputTokens = acc.outputTokens + msg.usage.outputTokens,
                totalTokens = acc.totalTokens + msg.usage.inputTokens + msg.usage.outputTokens,
                cacheReadTokens = acc.cacheReadTokens + msg.usage.cacheReadTokens,
                cacheWriteTokens = acc.cacheWriteTokens + msg.usage.cacheWriteTokens,
                durationMs = acc.durationMs + msg.usage.durationMs
            )
        }
        return ChatStreamEvent.Done(
            reason = reason,
            usage = if (totalUsage.totalTokens > 0) totalUsage else null,
            endReason = endReason.takeIf { it != "normal" }
        )
    }

    /**
     * Convert domain [Usage] to SSE [ChatStreamEvent.UsageInfo].
     * Returns null if usage has no meaningful token counts.
     */
    private fun Usage.toUsageInfo(includeDuration: Boolean = true): ChatStreamEvent.UsageInfo? {
        val hasTokens = inputTokens > 0 || outputTokens > 0
        val hasCacheTokens = cacheReadTokens != 0 || cacheWriteTokens != 0
        if (!hasTokens && !hasCacheTokens) return null
        return ChatStreamEvent.UsageInfo(
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            totalTokens = inputTokens + outputTokens,
            cacheReadTokens = cacheReadTokens,
            cacheWriteTokens = cacheWriteTokens,
            durationMs = if (includeDuration) durationMs else 0L
        )
    }

    /**
     * Create Error event.
     */
    fun createErrorEvent(
        error: Throwable,
        usage: ChatStreamEvent.UsageInfo? = null
    ): ChatStreamEvent = ChatStreamEvent.Error(
        reason = "error",
        errorMessage = error.message,
        usage = usage
    )
}