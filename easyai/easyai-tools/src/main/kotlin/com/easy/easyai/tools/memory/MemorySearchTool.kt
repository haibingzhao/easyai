package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryRef
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

internal class MemorySearchTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(val query: String)
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

        val results = mutableListOf<String>()
        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val entries = store.search(agentContext, query, scope)
            entries.forEach { entry ->
                // Record access for each search hit
                agentContext.memoryAccessTracker.recordAccess(MemoryRef(entry.name, entry.description, entry.type, scope))
                results.add("[${scope.name.lowercase()}:${entry.path}] ${entry.name} — ${entry.description}\n${entry.content.take(200)}")
            }
        }

        val text = if (results.isEmpty()) {
            "No memories found matching '$query'."
        } else {
            "Found ${results.size} memories:\n\n${results.joinToString("\n\n")}"
        }
        return ToolResult(content = listOf(TextContent(text)))
    }
}
