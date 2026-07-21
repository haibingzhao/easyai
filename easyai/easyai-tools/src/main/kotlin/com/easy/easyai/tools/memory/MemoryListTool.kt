package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

internal class MemoryListTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(val type: String? = null)
    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val typeFilter = (args["type"] as? String)?.let { MemoryType.fromDirName(it) }

        val sb = StringBuilder()

        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val entries = store.list(agentContext, scope, typeFilter)
            if (entries.isEmpty()) continue

            sb.appendLine("## ${scope.name} scope (${entries.size} entries)")
            sb.appendLine()
            entries.sortedBy { it.type.dirName }.forEach { entry ->
                sb.appendLine("- **${entry.name}** [${entry.type.dirName}] — ${entry.description}")
                sb.appendLine("  path: `${entry.path}` | created: ${entry.created ?: "unknown"} | updated: ${entry.updated ?: "unknown"}")
            }
            sb.appendLine()
        }

        val text = if (sb.isEmpty()) {
            "No memory entries found${if (typeFilter != null) " for type '${typeFilter.dirName}'" else ""}."
        } else {
            sb.toString()
        }
        return ToolResult(content = listOf(TextContent(text)))
    }
}
