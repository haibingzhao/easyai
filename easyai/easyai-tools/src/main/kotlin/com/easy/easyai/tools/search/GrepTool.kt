package com.easy.easyai.tools.search

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.tools.executeProcess
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

data class GrepParams(
    val pattern: String,
    val path: String? = null,
    val include: String? = null
)

class GrepTool(metadata: ToolMetadata, private val workDir: Path) : BaseToolDefinition(metadata) {
    override val executionMode: ToolExecutionMode get() = ToolExecutionMode.SEQUENTIAL
    override fun parameterType(): Class<*> = GrepParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val (pattern, searchPath) = parsePatternArgs(args, workDir)
            ?: return@withContext missingPatternError(toolCallId, name)
        val include = args["include"] as? String

        val cmd = mutableListOf(
            "rg",
            "--no-heading",
            "--color", "never",
            "--line-number"
        )
        if (include != null) {
            cmd.add("--glob")
            cmd.add(include)
        }
        cmd.add(pattern)
        cmd.add(searchPath.toString())

        val result = executeProcess(cmd, 30.seconds, workDir = searchPath, maxOutputBytes = Int.MAX_VALUE, onUpdate = onUpdate)

        if (result.timedOut) {
            return@withContext errorResult(toolCallId, name, "grep stalled: no output for 30s")
        }

        when (result.exitCode) {
            0 -> {
                val allLines = result.output.trimEnd().lines()
                val matchCount = allLines.size
                val truncated = matchCount > 100
                val lines = if (truncated) allLines.take(100) else allLines
                val result = buildString {
                    appendLine("Found $matchCount matches${if (truncated) " (showing first 100)" else ""}")
                    appendLine()
                    var currentFile = ""
                    for (line in lines) {
                        // rg --no-heading format: filepath:linenum:content
                        val parts = line.split(":", limit = 3)
                        if (parts.size >= 3) {
                            val filePath = parts[0]
                            if (filePath != currentFile) {
                                if (currentFile.isNotEmpty()) appendLine()
                                currentFile = filePath
                                appendLine("$filePath:")
                            }
                            appendLine("  Line ${parts[1]}: ${parts[2]}")
                        } else {
                            appendLine(line)
                        }
                    }
                    if (truncated) {
                        appendLine()
                        appendLine("(Results truncated. Consider using a more specific path or pattern.)")
                    }
                }
                ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = result.trimEnd())))
            }
            1 -> {
                ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "No matches found")))
            }
            else -> {
                errorResult(toolCallId, name, "grep error: ${result.output}")
            }
        }
    }

}