package com.easy.easyai.tools.search

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.tools.resolveSafe
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.*

data class LsParams(
    val path: String? = null
)

class LsTool(metadata: ToolMetadata, private val workDir: Path) : BaseToolDefinition(metadata) {
    override val executionMode: ToolExecutionMode get() = ToolExecutionMode.SEQUENTIAL
    override fun parameterType(): Class<*> = LsParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val dirPath = (args["path"] as? String)?.let { workDir.resolveSafe(it) } ?: workDir.normalize()

        if (!dirPath.isDirectory()) {
            return@withContext errorResult(toolCallId, name, "Error: not a directory: ${args["path"]}")
        }

        val entries = dirPath.listDirectoryEntries().map { entry ->
            val entryName = entry.fileName.toString()
            val type = if (entry.isDirectory()) "[D] " else "    "
            "$type$entryName"
        }
        ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = entries.joinToString("\n"))))
    }

}