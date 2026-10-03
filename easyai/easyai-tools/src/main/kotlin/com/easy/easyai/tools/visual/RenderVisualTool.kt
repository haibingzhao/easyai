package com.easy.easyai.tools.visual

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

/**
 * Parameters for [RenderVisualTool].
 */
data class RenderVisualParams(
    /** Short identifier for this visual: card title and download file name. */
    val title: String,
    /** Bare HTML or SVG fragment string (no document shell). */
    val code: String,
    /** Optional 1-4 short loading messages rotated while the client renders. */
    val loadingMessages: List<String>? = null
)

/**
 * Hands an HTML/SVG fragment to the client for inline sandboxed rendering.
 *
 * The fragment travels as tool-call arguments; the client intercepts the call and
 * renders it in a sandbox iframe inside the message flow. Execution here only
 * validates size and acknowledges delivery, so the model is never blocked on
 * rendering and must not repeat the fragment in its text reply.
 */
class RenderVisualTool(metadata: ToolMetadata) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = RenderVisualParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val title = args["title"] as? String
        if (title.isNullOrBlank()) {
            return errorResult("Error: 'title' parameter is required and must not be empty")
        }
        val code = args["code"] as? String
        if (code.isNullOrBlank()) {
            return errorResult("Error: 'code' parameter is required and must not be empty")
        }
        val bytes = code.toByteArray(Charsets.UTF_8).size
        if (bytes > MAX_FRAGMENT_BYTES) {
            return errorResult(
                "Error: fragment is $bytes bytes, exceeds the ${MAX_FRAGMENT_BYTES / 1024 / 1024}MB limit. " +
                    "Split the visual into smaller pieces or simplify it."
            )
        }
        return ToolResult(
            content = listOf(
                TextContent(
                    "Visual '$title' ($bytes bytes) delivered to the client for inline rendering. " +
                        "Do not repeat the fragment in your text reply."
                )
            )
        )
    }

    companion object {
        /** Max fragment size: 2MB — matches the client-side rendering cap. */
        const val MAX_FRAGMENT_BYTES = 2L * 1024L * 1024L
    }
}
