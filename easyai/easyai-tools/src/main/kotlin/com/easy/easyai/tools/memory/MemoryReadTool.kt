package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryRef
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

internal class MemoryReadTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class Parameters(val path: String)
    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val path = args["path"] as? String
        if (path.isNullOrBlank()) {
            return errorResult("Error: 'path' parameter is required.")
        }
        val owner = MemoryOwnerContext(agentContext.userId, agentContext.projectPath)

        // Try project scope first, then global
        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val content = store.read(path, scope, owner)
            if (content != null) {
                // Record access: find the matching entry to get metadata
                try {
                    val entry = store.list(scope, owner).find { it.path == path }
                    if (entry != null) {
                        agentContext.memoryAccessTracker.recordAccess(MemoryRef(entry.name, entry.description, entry.type, scope))
                    } else {
                        logger.debug("Memory entry not found in list for path: {} (scope: {})", path, scope)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to record memory access for path: {} (scope: {}): {}", path, scope, e.message)
                }
                return ToolResult(content = listOf(TextContent(content)))
            }
        }
        return errorResult("Memory file not found: $path")
    }
}
