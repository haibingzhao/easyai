package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.event.ProducerScope
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolExecutionEngine
import org.slf4j.LoggerFactory

/**
 * Handles pending tool calls from a previously interrupted assistant message.
 * This supports the resume scenario: if the last message is an AssistantMessage with
 * TOOL_USE stop reason and no ToolResultMessage follows, execute those toolCalls now.
 */
internal class PendingToolCallExecutor(
    private val toolExecutor: ToolExecutionEngine,
    private val tools: List<ToolDefinition>,
    private val messageListener: MessageListener?,
    private val agentContext: AgentContext,
    private val eventListeners: List<AgentEventListener> = emptyList()
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val logPrefix = agentLogPrefix(agentContext.parentAgentId)

    /**
     * Detects and executes pending tool calls from a previously interrupted assistant message.
     *
     * Resume scenario: if the last message is an AssistantMessage with TOOL_USE stop reason
     * and no ToolResultMessage follows, execute those toolCalls now.
     * Also handles skipped placeholder cleanup from previous permission pauses.
     *
     * This method always executes directly without re-evaluating permissions —
     * the resume caller has already approved (Allow Once / Always Allow) or denied
     * (results already saved) the tool calls before invoking this method.
     */
    suspend fun executePendingToolCallsIfNeeded(
        transcript: MutableList<EasyAiMessage>,
        scope: ProducerScope<AgentEvent, List<AssistantMessage>>
    ) {
        // Find the last AssistantMessage
        val lastAssistantIndex = transcript.indexOfLast { it is AssistantMessage }
        if (lastAssistantIndex == -1) return

        val lastAssistant = transcript[lastAssistantIndex] as AssistantMessage
        val allPendingToolCalls = lastAssistant.toolCalls()
        if (allPendingToolCalls.isEmpty()) return

        // Collect all existing tool results from ToolResultMessages after the AssistantMessage
        // Exclude skipped entries - they were generated when the loop paused
        // on a permission request and should be re-evaluated
        val existingResultToolCallIds = transcript.drop(lastAssistantIndex + 1)
            .filterIsInstance<ToolResultMessage>()
            .flatMap { it.toolResults }
            .filter { !it.isSkipped }
            .map { it.toolCallId }
            .toSet()

        // Find ToolResultMessages containing skipped entries for later cleanup
        val skippedToolResultMessages = transcript.drop(lastAssistantIndex + 1)
            .filterIsInstance<ToolResultMessage>()
            .filter { msg -> msg.toolResults.any { it.isSkipped } }

        // Filter: only execute tool calls that don't have results yet
        val unresolvedToolCalls = allPendingToolCalls.filter { it.id !in existingResultToolCallIds }

        // Build a set of tool names that should be skipped on resume
        val skipOnResumeToolNames = tools.filter { it.skipOnResume }.map { it.name }.toSet()

        // Filter out tools that should be skipped on resume (e.g., ask_question)
        // These tools returned WaitForUserContent and represent pending user interactions,
        // not tool calls that need re-execution
        val executableToolCalls = unresolvedToolCalls.filter { tc ->
            tc.name !in skipOnResumeToolNames
        }

        if (executableToolCalls.isEmpty()) {
            if (unresolvedToolCalls.isEmpty()) {
                // All tool calls have results, nothing to do
                return
            }
            logger.info("${logPrefix}Skipping pending toolCalls execution - only skipOnResume toolCalls found (waiting for user)")
            return
        }

        // Execute pending toolCalls directly.
        // Resume scenario: user has explicitly approved these tool calls
        // (via Allow Once / Always Allow), so we execute without re-evaluating permissions.
        // Deny scenario: tool results are already saved, so these toolCalls are filtered
        // out above and never reach this loop.
        logger.info("${logPrefix}Detected {} pending toolCalls from assistant message {}, executing...",
            executableToolCalls.size, lastAssistant.id)

        val executableToolCallIds = executableToolCalls.map { it.id }

        // Pre-execution hook: commit user changes before tools run
        eventListeners.forEach { listener ->
            try {
                listener.beforeToolExecutionBatch(agentContext, executableToolCallIds)
            } catch (e: Exception) {
                logger.warn("${logPrefix}Listener {} beforeToolExecutionBatch failed: {}",
                    listener::class.simpleName, e.message)
            }
        }

        // Batch-execute all pending toolCalls via engine (supports parallel execution).
        val results = toolExecutor.executeToolCalls(
            agentContext = agentContext,
            toolCalls = executableToolCalls,
            tools = tools,
            eventStream = scope,
            scope = scope,
            turnId = 0,
            messageId = lastAssistant.id
        )

        // Post-execution hook: commit LLM changes after tools complete
        val fileChangingToolNames = tools.filter { it.tracksFileChanges }.map { it.name }.toSet()
        val hasFileChangingTools = executableToolCalls.any { it.name in fileChangingToolNames }
        eventListeners.forEach { listener ->
            try {
                listener.afterToolExecutionBatch(
                    agentContext,
                    executableToolCallIds,
                    lastAssistant.id,
                    scope::push,
                    hasFileChangingTools
                )
            } catch (e: Exception) {
                logger.warn("${logPrefix}Listener {} afterToolExecutionBatch failed: {}",
                    listener::class.simpleName, e.message)
            }
        }

        // Build and add ToolResultMessage
        val toolCallMap = executableToolCalls.associateBy { it.id }
        val toolResultEntries = results.mapNotNull { r ->
            val tc = toolCallMap[r.toolCallId]
            if (r.needPause) {
                null // Skip needPause results (permission pause again)
            } else {
                tc?.let {
                    ToolResultEntry(
                        toolCallId = r.toolCallId,
                        toolName = it.name,
                        result = r.resultText,
                        durationMs = r.durationMs,
                        isError = r.isError,
                        exitCode = r.exitCode,
                        mimeType = r.mimeType,
                        isSkipped = r.isSkipped,
                        usage = r.usage
                    )
                }
            }
        }

        if (toolResultEntries.isNotEmpty()) {
            val toolResultMessage = ToolResultMessage(toolResults = toolResultEntries)
            transcript.add(toolResultMessage)
            messageListener?.onMessageAdded(listOf(toolResultMessage))
            logger.info("${logPrefix}Added ToolResultMessage with {} entries for resume scenario", toolResultEntries.size)
        }

        // Clean up skipped placeholder entries from old ToolResultMessages
        // This ensures DB consistency after tool re-execution
        for (oldMsg in skippedToolResultMessages) {
            val nonSkippedResults = oldMsg.toolResults.filter { !it.isSkipped }
            if (nonSkippedResults.isEmpty()) {
                // All entries were skipped - remove the message from transcript
                transcript.remove(oldMsg)
                logger.info("${logPrefix}Removed empty ToolResultMessage {} (all entries were skipped)", oldMsg.id)
                // Skip DB update — the skipped entries are already filtered in existingResultToolCallIds
            } else {
                // Update the message with non-skipped entries only
                val updatedMsg = oldMsg.copy(toolResults = nonSkippedResults)
                val index = transcript.indexOf(oldMsg)
                if (index >= 0) {
                    transcript[index] = updatedMsg
                }
                // Persist the update to DB
                messageListener?.onMessageUpdated(oldMsg.id, updatedMsg)
                logger.info("${logPrefix}Cleaned {} skipped entries from ToolResultMessage {}", 
                    oldMsg.toolResults.size - nonSkippedResults.size, oldMsg.id)
            }
        }
    }
}