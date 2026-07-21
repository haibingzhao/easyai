package com.easy.easyai.tools.search

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.tools.executeProcess
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
import kotlin.time.Duration.Companion.seconds

data class GlobParams(
    val pattern: String,
    val path: String? = null
)

class GlobTool(metadata: ToolMetadata, private val workDir: Path) : BaseToolDefinition(metadata) {
    override val executionMode: ToolExecutionMode get() = ToolExecutionMode.SEQUENTIAL
    override fun parameterType(): Class<*> = GlobParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val pattern = args["pattern"] as? String ?: return@withContext ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "Error: missing 'pattern' parameter", isError = true)),
            isError = true
        )
        val searchPath = (args["path"] as? String)?.let { workDir.resolveSafe(it) } ?: workDir

        val cmd = listOf(
            "rg",
            "--no-config",
            "--files",
            "--glob=$pattern",
            "--glob=!**/.git/**",
            "."
        )

        val result = executeProcess(cmd, 30.seconds, workDir = searchPath, maxOutputBytes = Int.MAX_VALUE, onUpdate = onUpdate)

        if (result.timedOut) {
            return@withContext ToolResult(
                content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "glob timeout after 30s", isError = true)),
                isError = true
            )
        }

        when (result.exitCode) {
            0 -> {
                val files = result.output.trimEnd().lines().filter { it.isNotBlank() }
                val limit = 100
                val truncated = files.size >= limit
                val displayFiles = files.take(limit)

                val result = buildString {
                    if (displayFiles.isEmpty()) {
                        append("No files found")
                    } else {
                        for (file in displayFiles) {
                            val resolved = searchPath.resolve(file.removePrefix("./")).normalize()
                            appendLine(resolved.toString())
                        }
                        if (truncated) {
                            appendLine()
                            appendLine("(Results are truncated: showing first $limit results. Consider using a more specific path or pattern.)")
                        }
                    }
                }
                ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = result.trimEnd())))
            }
            else -> {
                ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "glob error: ${result.output}", isError = true)), isError = true)
            }
        }
    }
}
