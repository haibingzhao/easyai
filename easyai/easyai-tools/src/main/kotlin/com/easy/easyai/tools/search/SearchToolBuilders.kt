package com.easy.easyai.tools.search

import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.tools.file.AbstractFileReadToolBuilder
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Builder for [GrepTool].
 */
@Component
class GrepToolBuilder : AbstractFileReadToolBuilder() {
    override val metadata = ToolMetadata(
        name = "grep",
        description = """Fast content search tool that works with any codebase size.
- Searches file contents using regular expressions
- Supports full regex syntax (eg. "log.*Error", "function\s+\w+", etc.)
- Filter files by pattern with the include parameter (eg. "*.js", "*.{ts,tsx}")
- Returns file paths and line numbers with matching lines
- Use this tool when you need to find files containing specific patterns
- Do NOT use bash tool for content search, use this tool instead.""",
        permissionCategory = "file",
        uiRenderer = "grep",
        patternKeys = listOf("path", "file", "filepath")
    )

    override fun createTool(workDir: Path): ToolDefinition = GrepTool(metadata, workDir)
}

/**
 * Builder for [GlobTool].
 */
@Component
class GlobToolBuilder : AbstractFileReadToolBuilder() {
    override val metadata = ToolMetadata(
        name = "glob",
        description = """Fast file pattern matching tool that works with any codebase size.
- Supports glob patterns like "**/*.js" or "src/**/*.ts"
- Returns matching file paths
- Use this tool when you need to find files by name patterns
- Do NOT use bash tool for file name matching, use this tool instead.""",
        permissionCategory = "file",
        uiRenderer = "file_search",
        patternKeys = listOf("path", "file", "filepath")
    )

    override fun createTool(workDir: Path): ToolDefinition = GlobTool(metadata, workDir)
}

/**
 * Builder for [LsTool].
 */
@Component
class LsToolBuilder : AbstractFileReadToolBuilder() {
    override val metadata = ToolMetadata(
        name = "ls",
        description = "List directory contents",
        permissionCategory = "file",
        uiRenderer = "file_search",
        patternKeys = listOf("path", "file", "filepath")
    )

    override fun createTool(workDir: Path): ToolDefinition = LsTool(metadata, workDir)
}
