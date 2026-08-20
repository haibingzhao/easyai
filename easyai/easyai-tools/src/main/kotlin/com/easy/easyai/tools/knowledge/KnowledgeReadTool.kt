package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

internal class KnowledgeReadTool(
    metadata: ToolMetadata,
    private val store: KnowledgeStore
) : BaseToolDefinition(metadata) {

    data class Parameters(val key: String)
    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val key = args["key"] as? String
        if (key.isNullOrBlank()) {
            return errorResult("Error: 'key' parameter is required.")
        }
        val userId = agentContext.userId ?: DEFAULT_USER_ID

        val content = store.read(userId, key)
            ?: return errorResult("Knowledge entry not found: $key")
        return ToolResult(content = listOf(TextContent(content)))
    }

    private companion object {
        /** Fallback user id when no authenticated user is present (matches KnowledgeController behavior). */
        const val DEFAULT_USER_ID = "system"
    }
}
