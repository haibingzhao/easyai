package com.easy.easyai.tools.visual

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope

/**
 * Parameters for [RenderVisualTool].
 */
data class RenderVisualParams(
    @param:JsonPropertyDescription(
        "Required. Short human-readable name for this visual, e.g. 'Quarterly revenue comparison'. " +
            "It labels the rendered card and names exported files; never omit it or leave it empty."
    )
    val title: String,
    @param:JsonPropertyDescription(
        "Required. Bare HTML or SVG fragment string (no document shell); " +
            "see the input and style contract in the tool description."
    )
    val code: String,
    @param:JsonPropertyDescription("Optional 1-4 short loading messages rotated while the client renders.")
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
            return errorResult(
                "Error: 'title' is missing or blank. It is a required short human-readable name for this visual " +
                    "(e.g. 'Quarterly revenue comparison'), shown as the card header and used as the export file name. " +
                    "Call render_visual again with both 'title' and 'code' set."
            )
        }
        val code = args["code"] as? String
        if (code.isNullOrBlank()) {
            return errorResult("Error: 'code' parameter is required and must not be empty")
        }
        if (ELIDED_PLACEHOLDER.matches(code)) {
            return errorResult(
                "Error: 'code' is the context-elision placeholder, not a fragment — the original markup is not in " +
                    "your context. Author the fragment from scratch and call render_visual again; never copy the placeholder."
            )
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

        /**
         * Matches the prose placeholder that older sessions persisted in place of an elided
         * fragment, so a replayed or parroted one fails loudly instead of rendering. The
         * render_visual context projector no longer emits it.
         */
        val ELIDED_PLACEHOLDER = Regex("""^\[fragment elided from context: \d+ bytes.*]$""")
    }
}
