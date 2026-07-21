package com.easy.easyai.tools.file

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.*
import com.easy.easyai.tools.resolveSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.nio.file.Path
import kotlin.io.path.*

data class WriteFileParams(
    val path: String,
    val content: String,
    val overwrite: Boolean? = null
)

class WriteTool(metadata: ToolMetadata, private val workDir: Path) : BaseToolDefinition(metadata) {
    override val executionMode: ToolExecutionMode get() = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = WriteFileParams::class.java

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
        val content = args["content"] as? String ?: return@withContext ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "Error: missing 'content' parameter", isError = true)),
            isError = true
        )
        val overwrite = when (val v = args["overwrite"]) {
            is Boolean -> v
            is String -> v.equals("true", ignoreCase = true)
            else -> false
        }
        val file = workDir.resolveSafe(pathStr)

        if (file.exists()) {
            if (!overwrite) {
                val existingLines = file.readLines().size
                return@withContext ToolResult(
                    content = listOf(ToolResultContent(
                        toolCallId = toolCallId,
                        toolName = name,
                        output = "File already exists: $pathStr ($existingLines lines). " +
                            "Use the edit tool to modify existing files, " +
                            "or set overwrite=true to replace the entire file content.",
                        isError = true
                    )),
                    isError = true
                )
            }

            // Overwrite mode: read old content, write new content, provide diff preview
            val oldContent = file.readText()
            file.writeText(content)
            val output = buildOverwriteOutput(pathStr, oldContent, content)
            ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = output)))
        } else {
            file.createParentDirectories()
            file.writeText(content)
            ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "File created: $pathStr")))
        }
    }

    private fun buildOverwriteOutput(pathStr: String, oldContent: String, newContent: String): String {
        val oldLineCount = oldContent.lines().size
        val newLineCount = newContent.lines().size
        return buildString {
            appendLine("Overwrote file: $pathStr")
            appendLine("Previous: $oldLineCount lines → New: $newLineCount lines")
            appendLine("```diff")
            append(formatDiffSection("-", oldContent))
            append(formatDiffSection("+", newContent))
            appendLine("```")
        }
    }

    private fun formatDiffSection(prefix: String, content: String): String {
        val maxLines = 20
        val lines = content.lines()
        return buildString {
            for (line in lines.take(maxLines)) {
                appendLine("$prefix$line")
            }
            if (lines.size > maxLines) {
                appendLine("$prefix... (${lines.size - maxLines} more lines)")
            }
        }
    }
}