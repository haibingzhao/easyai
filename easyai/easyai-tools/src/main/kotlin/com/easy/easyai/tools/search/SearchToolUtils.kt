package com.easy.easyai.tools.search

import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.tools.resolveSafe
import java.nio.file.Path

/**
 * Parsed common arguments for pattern-based search tools (grep, glob).
 */
internal data class PatternArgs(
    val pattern: String,
    val searchPath: Path
)

/**
 * Extracts the required "pattern" and optional "path" arguments shared by search tools.
 * Returns null if the pattern parameter is missing.
 */
internal fun parsePatternArgs(args: Map<String, Any?>, workDir: Path): PatternArgs? {
    val pattern = args["pattern"] as? String ?: return null
    val searchPath = (args["path"] as? String)?.let { workDir.resolveSafe(it) } ?: workDir
    return PatternArgs(pattern, searchPath)
}

/**
 * Builds the standard error [ToolResult] for a missing pattern parameter.
 */
internal fun missingPatternError(toolCallId: String, toolName: String): ToolResult = ToolResult(
    content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = toolName, output = "Error: missing 'pattern' parameter", isError = true)),
    isError = true
)
