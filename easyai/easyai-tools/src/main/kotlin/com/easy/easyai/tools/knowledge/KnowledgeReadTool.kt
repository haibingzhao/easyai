package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

internal class KnowledgeReadTool(
    metadata: ToolMetadata,
    private val store: KnowledgeStore,
    private val knowledgeOwnerId: String,
    private val accessGuard: KnowledgeAccessGuard,
    private val accessContext: KnowledgeAccessContext,
) : BaseToolDefinition(metadata) {

    data class Parameters(val id: String)
    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val id = (args["id"] as? String)?.trim()
        if (id.isNullOrBlank()) {
            return errorResult("Error: 'id' parameter is required.")
        }

        if (!accessGuard.canReadEntry(accessContext, knowledgeOwnerId, id)) {
            return errorResult("Knowledge article not found: $id")
        }
        val content = store.read(knowledgeOwnerId, id)
            ?: return errorResult("Knowledge article not found: $id")
        return ToolResult(content = listOf(TextContent(content)))
    }
}
