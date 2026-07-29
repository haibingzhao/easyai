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

        // Build ToolResultEntries from execution results
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
            val targetMsg = skippedToolResultMessages.firstOrNull()
            if (targetMsg != null) {
                // Merge into the existing ToolResultMessage that contains skipped placeholders.
                // Keep non-skipped entries (from previously executed tools) and append new results.
                // This avoids creating consecutive ToolResultMessages which would confuse the LLM.
                val nonSkippedResults = targetMsg.toolResults.filter { !it.isSkipped }
                val mergedResults = nonSkippedResults + toolResultEntries
                val updatedMsg = ToolResultMessage(id = targetMsg.id, toolResults = mergedResults)
                val index = transcript.indexOf(targetMsg)
                if (index >= 0) {
                    transcript[index] = updatedMsg
                } else {
                    transcript.add(updatedMsg)
                }
                messageListener?.onMessageUpdated(targetMsg.id, updatedMsg)
                logger.info("${logPrefix}Merged {} new results into ToolResultMessage {} ({} non-skipped kept, {} skipped replaced)",
                    toolResultEntries.size, targetMsg.id, nonSkippedResults.size, targetMsg.toolResults.size - nonSkippedResults.size)

                // Delete any additional skipped messages (rare edge case — normally only one exists)
                for (extraMsg in skippedToolResultMessages.drop(1)) {
                    transcript.remove(extraMsg)
                    messageListener?.onMessageDeleted(extraMsg.id)
                    logger.info("${logPrefix}Deleted redundant skipped ToolResultMessage {}", extraMsg.id)
                }
            } else {
                // No existing skipped message — create a new ToolResultMessage
                val toolResultMessage = ToolResultMessage(toolResults = toolResultEntries)
                transcript.add(toolResultMessage)
                messageListener?.onMessageAdded(listOf(toolResultMessage))
                logger.info("${logPrefix}Added ToolResultMessage with {} entries for resume scenario", toolResultEntries.size)
            }
        }
    }
}