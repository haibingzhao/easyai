package com.easy.easyai.tools.question

import org.slf4j.LoggerFactory
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.core.tool.question.AskQuestionParameter
import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext

/**
 * Tool for asking the user questions during agent execution.
 * Returns WaitForUserContent immediately - does not block waiting for user response.
 * The AgentLoop will detect WaitForUserContent and end the SSE stream.
 * User response is handled via the REST API and resumes execution.
 */
class AskQuestionTool(metadata: ToolMetadata) : BaseToolDefinition(metadata) {

    companion object {
        private val logger = LoggerFactory.getLogger(AskQuestionTool::class.java)
        private val objectMapper = SharedObjectMapper.instance
    }

    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = AskQuestionParameter::class.java

    @Suppress("UNCHECKED_CAST")
    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            val paramsJson = objectMapper.writeValueAsString(args)
            val params = objectMapper.readValue(paramsJson, AskQuestionParameter::class.java)

            if (params.questions.isEmpty()) {
                return@withContext ToolResult(
                    content = listOf(TextContent(text = "Error: at least one question is required")),
                    isError = true
                )
            }

            logger.info("Created pending question: {} with {} question(s) in tool call", params.questions.size, toolCallId)

            // Return needPause Result - AgentLoop will detect this and end the SSE stream
            ToolResult(content=emptyList(), needPause = true, pauseReason = "ask_question")
        } catch (e: Exception) {
            logger.error("Error executing ask_question tool in tool call {}", toolCallId, e)
            ToolResult(
                content = listOf(TextContent(text = "Failed to ask question: ${e.message}")),
                isError = true
            )
        }
    }
}