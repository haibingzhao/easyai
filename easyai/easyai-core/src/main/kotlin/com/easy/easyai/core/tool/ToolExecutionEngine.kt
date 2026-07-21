package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.*
import com.fasterxml.jackson.annotation.JsonIgnore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.slf4j.LoggerFactory
import com.easy.easyai.common.util.SharedObjectMapper
import tools.jackson.module.kotlin.readValue

/**
 * Result of executing a single tool call, containing the tool call ID, result text, and duration.
 */
data class ToolCallResult(
    val toolCallId: String,
    val resultText: String,
    val isError: Boolean = false,
    val durationMs: Long? = null,
    val exitCode: Int? = null,
    val mimeType: String = "text/plain",
    @get:JsonIgnore
    val needPause: Boolean = false,
    /** Reason for pause when [needPause] is true (e.g., "permission_request", "ask_question"). Null when not paused. */
    val pauseReason: String? = null,
    val isSkipped: Boolean = false,
    /** Token usage from this tool execution (e.g., sub-agent LLM calls). Null if not applicable. */
    val usage: Usage? = null
)

interface ToolExecutionEngine {
    suspend fun executeToolCalls(
        agentContext: AgentContext,
        toolCalls: List<ToolCallContent>,
        tools: List<ToolDefinition>,
        eventStream: ProducerScope<AgentEvent, List<AssistantMessage>>,
        scope: CoroutineScope,
        turnId: Int,
        messageId: String? = null
    ): List<ToolCallResult>
}

class DefaultToolExecutionEngine: ToolExecutionEngine {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val objectMapper = SharedObjectMapper.instance

    override suspend fun executeToolCalls(
        agentContext: AgentContext,
        toolCalls: List<ToolCallContent>,
        tools: List<ToolDefinition>,
        eventStream: ProducerScope<AgentEvent, List<AssistantMessage>>,
        scope: CoroutineScope,
        turnId: Int,
        messageId: String?
    ): List<ToolCallResult> {
        val hasSequential = toolCalls.any { tc ->
            tools.find { it.name == tc.name }?.executionMode == ToolExecutionMode.SEQUENTIAL
        }

        val results = if (hasSequential) {
            executeSequential(agentContext, toolCalls, tools, eventStream, scope, turnId, messageId)
        } else {
            executeParallel(agentContext, toolCalls, tools, eventStream, scope, turnId, messageId)
        }

        return results
    }

    private suspend fun executeSequential(
        agentContext: AgentContext,
        toolCalls: List<ToolCallContent>,
        tools: List<ToolDefinition>,
        eventStream: ProducerScope<AgentEvent, List<AssistantMessage>>,
        scope: CoroutineScope,
        turnId: Int,
        messageId: String?
    ): List<ToolCallResult> {
        val results = mutableListOf<ToolCallResult>()
        for (tc in toolCalls) {
            results.add(executeSingle(agentContext, tc, tools, eventStream, scope, turnId, messageId))
        }
        return results
    }

    private suspend fun executeParallel(
        agentContext: AgentContext,
        toolCalls: List<ToolCallContent>,
        tools: List<ToolDefinition>,
        eventStream: ProducerScope<AgentEvent, List<AssistantMessage>>,
        scope: CoroutineScope,
        turnId: Int,
        messageId: String?
    ): List<ToolCallResult> {
        val deferredResults = toolCalls.map { tc ->
            scope.async { executeSingle(agentContext, tc, tools, eventStream, this, turnId, messageId) }
        }
        return deferredResults.awaitAll()
    }

    private suspend fun executeSingle(
        agentContext: AgentContext,
        tc: ToolCallContent,
        tools: List<ToolDefinition>,
        eventStream: ProducerScope<AgentEvent, List<AssistantMessage>>,
        scope: CoroutineScope,
        turnId: Int,
        messageId: String?
    ): ToolCallResult {
        val sessionId = agentContext.sessionId ?: "default"
        val tool = tools.find { it.name == tc.name }
        if (tool == null) {
            val availableTools = tools.joinToString(", ") { it.name }
            val errorMsg = "Unknown tool: '${tc.name}'. Available tools: $availableTools"
            val errorResult = ToolResult(
                content = listOf(ToolResultContent(toolCallId = tc.id, toolName = tc.name, output = errorMsg, isError = true)),
                isError = true
            )
            eventStream.push(ToolCallStatusUpdateEvent(tc.id, tc.name, ToolCallStatus.FAILED, turnId, sessionId))
            eventStream.push(ToolExecutionEndEvent(tc.id, tc.name, errorResult, turnId = turnId, sessionId = sessionId, messageId = messageId, isError = true))
            return ToolCallResult(
                toolCallId = tc.id,
                resultText = extractTextContent(errorResult),
                isError = true,
                durationMs = 0
            )
        }
        val argsPreview = if (tc.arguments.length > 100) tc.arguments.take(200) + "..." else tc.arguments
        logger.trace("[Turn ${turnId}] Executing tool call ${tc.name}(${tc.id}) with arguments: $argsPreview")
        val args = parseArgs(tc.arguments)
        val startTime = System.currentTimeMillis()

        // Notify status change: PENDING → RUNNING
        eventStream.push(ToolCallStatusUpdateEvent(tc.id, tc.name, ToolCallStatus.RUNNING, turnId, sessionId))

        eventStream.push(ToolExecutionStartEvent(tc.id, tc.name, args, turnId, sessionId, tracksFileChanges = tool.tracksFileChanges))

        val result = try {
            tool.execute(agentContext, tc.id, messageId, args, scope) { update ->
                when (update) {
                    is ToolUpdate.SubAgentEvent -> {
                        // Inject subAgent context into the inner event and push directly
                        val enriched = update.event.withSubAgentContext(tc.id, update.agentName)
                        eventStream.push(enriched)
                    }
                    else -> eventStream.push(ToolExecutionUpdateEvent(tc.id, update, turnId, sessionId))
                }
            }
        } catch (e: CancellationException) {
            // Re-throw CancellationException to propagate coroutine cancellation.
            // Swallowing it would prevent the agent loop from stopping on abort/cancel.
            throw e
        } catch (e: Exception) {
            val errorResult = ToolResult(
                content = listOf(ToolResultContent(toolCallId = tc.id, toolName = tc.name, output = "Error: ${e.message}", isError = true)),
                isError = true
            )
            // Notify status change: RUNNING → FAILED
            eventStream.push(ToolCallStatusUpdateEvent(tc.id, tc.name, ToolCallStatus.FAILED, turnId, sessionId))
            eventStream.push(ToolExecutionEndEvent(tc.id, tc.name, errorResult, turnId = turnId, sessionId = sessionId, messageId = messageId, isError = true, tracksFileChanges = tool.tracksFileChanges))
            val durationMs = System.currentTimeMillis() - startTime
            logger.debug("[Turn ${turnId}] Tool call ${tc.name}(${tc.id}) failed in ${durationMs}ms with error: ${e.message}")
            return ToolCallResult(
                toolCallId = tc.id,
                resultText = extractTextContent(errorResult),
                isError = true,
                durationMs = durationMs
            )
        }

        // If needPause result, skip status and end events - AgentLoop will handle the pause
        if (!result.needPause) {
            // Notify status change: RUNNING → COMPLETED or FAILED
            val status = if (result.isError) ToolCallStatus.FAILED else ToolCallStatus.COMPLETED
            eventStream.push(ToolCallStatusUpdateEvent(tc.id, tc.name, status, turnId, sessionId))
            // Extract tool usage from ToolResult and pass to event
            val toolUsage = result.usage
            eventStream.push(ToolExecutionEndEvent(tc.id, tc.name, result, turnId = turnId, sessionId = sessionId, messageId = messageId, isError = result.isError, tracksFileChanges = tool.tracksFileChanges, usage = toolUsage))
        }
        val durationMs = System.currentTimeMillis() - startTime
        logger.trace("[Turn ${turnId}] Tool call ${tc.name}(${tc.id}) completed in ${durationMs}ms")
        // Propagate tool usage to ToolCallResult
        val toolUsage = result.usage
        return ToolCallResult(
            toolCallId = tc.id,
            resultText = extractTextContent(result),
            durationMs = durationMs,
            needPause = result.needPause,
            pauseReason = result.pauseReason,
            exitCode = result.content.filterIsInstance<ToolResultContent>().firstOrNull()?.exitCode,
            mimeType = result.content.filterIsInstance<ToolResultContent>().firstOrNull()?.mimeType ?: "text/plain",
            isError = result.isError,
            usage = toolUsage
        )
    }

    private fun parseArgs(json: String): Map<String, Any?> =
        objectMapper.readValue(json)

    private fun extractTextContent(result: ToolResult): String {
        return result.content.joinToString("\n") { block ->
            when (block) {
                is TextContent -> block.text
                is ToolResultContent -> block.output
                else -> block.type
            }
        }
    }
}