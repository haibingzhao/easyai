package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

internal class KnowledgeSearchTool(
    metadata: ToolMetadata,
    private val store: KnowledgeStore
) : BaseToolDefinition(metadata) {

    data class Parameters(
        val query: String,
        val source: String? = null,
        val kcategory: String? = null
    )
    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val query = args["query"] as? String
        if (query.isNullOrBlank()) {
            return errorResult("Error: 'query' parameter is required and must not be empty.")
        }
        val source = args["source"] as? String
        val kcategory = args["kcategory"] as? String
        val userId = agentContext.userId ?: DEFAULT_USER_ID

        val entries = store.list(userId, source = source, kcategory = kcategory, query = query)

        val text = if (entries.isEmpty()) {
            "No knowledge entries found matching '$query'."
        } else {
            val results = entries.map { entry ->
                "[${entry.key}] ${entry.title} — ${entry.description}\n${entry.content.take(CONTENT_PREVIEW_LIMIT)}"
            }
            "Found ${results.size} knowledge entries:\n\n${results.joinToString("\n\n")}"
        }
        return ToolResult(content = listOf(TextContent(text)))
    }

    private companion object {
        /** Max characters of entry content returned per search hit, reducing follow-up knowledge_read calls. */
        const val CONTENT_PREVIEW_LIMIT = 1500

        /** Fallback user id when no authenticated user is present (matches KnowledgeController behavior). */
        const val DEFAULT_USER_ID = "system"
    }
}
