package com.easy.easyai.tools.file

import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Builder for [ReadTool].
 */
@Component
class ReadToolBuilder : AbstractFileReadToolBuilder() {
    override val metadata = ToolMetadata(
        name = "read",
        description = "Read file contents with optional line range",
        permissionCategory = "file",
        uiRenderer = "read",
        patternKeys = listOf("path", "file", "filepath")
    )

    override fun createTool(workDir: Path): ToolDefinition = ReadTool(metadata, workDir)
}

/**
 * Builder for [WriteTool].
 */
@Component
class WriteToolBuilder : AbstractFileWriteToolBuilder() {
    override val metadata = ToolMetadata(
        name = "write",
        description = """Create a new file or overwrite an existing file.
By default, fails if the file already exists — use edit for partial modifications.
Set overwrite=true to replace the entire contents of an existing file.""",
        permissionCategory = "file",
        tracksFileChanges = true,
        uiRenderer = "file_edit",
        patternKeys = listOf("path", "file", "filepath")
    )

    override fun createTool(workDir: Path): ToolDefinition = WriteTool(metadata, workDir)
}

/**
 * Builder for [EditTool].
 */
@Component
class EditToolBuilder : AbstractFileWriteToolBuilder() {
    override val metadata = ToolMetadata(
        name = "edit",
        description = """Replace exact text in one file.
Parameters:
- path: File path to edit
- oldString: Exact text to replace (must match exactly, including whitespace and indentation)
- newString: Replacement text, which must differ from oldString
- replaceAll: Replace all exact occurrences of oldString (default false). If false and multiple matches found, an error is returned.""",
        permissionCategory = "file",
        tracksFileChanges = true,
        uiRenderer = "file_edit",
        patternKeys = listOf("path", "file", "filepath")
    )

    override fun createTool(workDir: Path): ToolDefinition = EditTool(metadata, workDir)
}
