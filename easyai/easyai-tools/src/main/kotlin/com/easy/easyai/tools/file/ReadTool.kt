package com.easy.easyai.tools.file

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

/** Maximum file size allowed for reading (1 MB). Files larger than this are rejected. */
internal const val MAX_READ_FILE_SIZE_BYTES: Long = 1_048_576L

data class ReadFileParams(
    val path: String,
    val offset: Int? = null,
    val limit: Int? = null
)

class ReadTool(metadata: ToolMetadata, private val workDir: Path) : BaseToolDefinition(metadata) {
    override val executionMode: ToolExecutionMode get() = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = ReadFileParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val pathStr = args["path"] as? String ?: return@withContext ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "Error: missing 'path' parameter", isError = true)),
            isError = true
        )
        val file = workDir.resolveSafe(pathStr)
        if (!file.exists()) return@withContext ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "Error: file not found: $pathStr", isError = true)),
            isError = true
        )

        val hasPagination = args.containsKey("offset") || args.containsKey("limit")
        val fileSize = file.fileSize()
        if (!hasPagination && fileSize > MAX_READ_FILE_SIZE_BYTES) return@withContext ToolResult(
            content = listOf(ToolResultContent(
                toolCallId = toolCallId,
                toolName = name,
                output = "Error: file too large: ${fileSize / 1024}KB (max 1MB). " +
                    "Use offset/limit parameters to read specific portions, " +
                    "or bash with 'head -n N' / 'tail -n N'.",
                isError = true
            )),
            isError = true
        )

        val lines = file.readLines()
        val offset = (args["offset"] as? Number)?.toInt() ?: 0
        val limit = (args["limit"] as? Number)?.toInt() ?: lines.size
        val selected = lines.drop(offset.coerceAtLeast(0)).take(limit.coerceAtLeast(1))
        val totalLines = lines.size
        val header = if (selected.size < totalLines) "Showing lines ${offset + 1}-${offset + selected.size} of $totalLines\n" else ""
        ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = header + selected.joinToString("\n"))))
    }

}