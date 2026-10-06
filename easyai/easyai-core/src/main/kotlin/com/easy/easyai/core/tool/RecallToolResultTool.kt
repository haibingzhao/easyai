package com.easy.easyai.core.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.message.ToolFoldProjection
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.model.ToolResultMessage
import kotlinx.coroutines.CoroutineScope

/**
 * Hidden system tool that returns the original (unfolded) content behind a folded placeholder.
 *
 * [com.easy.easyai.core.message.ToolFoldProjection] replaces tool-call arguments and results of
 * historical runs with one-line placeholders carrying a `ref` of the form
 * `<messageId>#<toolCallId>`; this tool resolves such a ref against the live transcript, which
 * always holds the originals.
 *
 * Deliberately NOT registered in the ToolRegistry or exposed in agent tool-selection UIs:
 * it is injected into the prompt tool list only while folds exist for the current run,
 * and it needs no ToolBuilder because the permission system allows unregistered tools by default.
 */
internal class RecallToolResultTool(
    private val transcriptProvider: () -> List<EasyAiMessage>
) : ToolDefinition {

    override val name: String = TOOL_NAME
    override val description: String =
        "Retrieve the original content of a folded tool call or tool result from an earlier part of this conversation. " +
        "Use the ref value shown in a folded placeholder line, e.g. recall_tool_result(ref=\"msg_123#tc_456\"). " +
        "Only refs shown in folded placeholders are recallable."
    override val inputSchema: String = """
        {
          "type": "object",
          "properties": {
            "ref": {
              "type": "string",
              "description": "The ref from a folded placeholder line, formatted as <messageId>#<toolCallId>"
            }
          },
          "required": ["ref"]
        }
    """.trimIndent()

    override suspend fun execute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val ref = (args["ref"] as? String)?.trim().orEmpty()
        if (ref.isEmpty()) {
            return errorResult("Missing required argument 'ref'. Copy the ref value from a folded placeholder line.")
        }
        val parts = ref.split(ToolFoldProjection.REF_SEPARATOR, limit = 2)
        val msgId = parts[0].trim()
        val callId = parts.getOrNull(1)?.trim()
        if (msgId.isEmpty() || callId.isNullOrEmpty()) {
            return errorResult("Invalid ref '$ref'. Expected the format <messageId>#<toolCallId> from a folded placeholder line.")
        }

        val transcript = transcriptProvider()
        val resultMessage = transcript.firstOrNull { it is ToolResultMessage && it.id == msgId } as? ToolResultMessage
        val entry = resultMessage?.toolResults?.firstOrNull { it.toolCallId == callId }
        if (entry != null) {
            val output = buildString {
                appendLine("[recalled: ${entry.toolName} ${entry.toolCallId} from ref $ref]")
                append(entry.result)
            }
            return ToolResult(
                content = listOf(
                    ToolResultContent(
                        toolCallId = toolCallId,
                        toolName = name,
                        output = output,
                        exitCode = entry.exitCode,
                        durationMs = entry.durationMs,
                        mimeType = entry.mimeType,
                        isError = entry.isError
                    )
                ),
                isError = false
            )
        }

        // Folded tool-call placeholders point at the assistant message holding the call,
        // so a miss on the result side falls back to the original arguments.
        val assistantMessage = transcript.firstOrNull { it is AssistantMessage && it.id == msgId } as? AssistantMessage
        val call = assistantMessage?.content?.firstOrNull { it is ToolCallContent && it.id == callId } as? ToolCallContent
        if (call != null) {
            val output = buildString {
                appendLine("[recalled call arguments: ${call.name} ${call.id} from ref $ref]")
                append(call.arguments)
            }
            return ToolResult(
                content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = output)),
                isError = false
            )
        }

        if (resultMessage == null && assistantMessage == null) {
            return errorResult("No folded tool result found for ref '$ref'. It may have been removed by context compaction.")
        }
        return errorResult("No tool call '$callId' in message '$msgId'. Use the exact ref from the folded placeholder.")
    }

    private fun errorResult(message: String): ToolResult = ToolResult(
        content = listOf(TextContent(message)),
        isError = true
    )

    companion object {
        const val TOOL_NAME = "recall_tool_result"
    }
}
