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
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeText

data class EditFileParams(
    val path: String,
    val oldString: String,
    val newString: String,
    val replaceAll: Boolean? = null
)

private fun normalizeLineEndings(text: String): String = text.replace("\r\n", "\n")

private fun detectLineEnding(text: String): String = if (text.contains("\r\n")) "\r\n" else "\n"

private fun convertToLineEnding(text: String, ending: String): String {
    val normalized = normalizeLineEndings(text)
    return if (ending == "\r\n") normalized.replace("\n", "\r\n") else normalized
}

private data class BomSplit(val bom: Boolean, val text: String)

private fun splitBom(text: String): BomSplit =
    if (text.startsWith("\uFEFF")) BomSplit(bom = true, text = text.substring(1))
    else BomSplit(bom = false, text = text)

private fun joinBom(text: String, bom: Boolean): String = if (bom) "\uFEFF$text" else text

private fun countOccurrences(content: String, search: String): Int {
    if (search.isEmpty()) return content.length + 1
    var count = 0
    var offset = 0
    while (true) {
        val idx = content.indexOf(search, offset)
        if (idx == -1) break
        count++
        offset = idx + search.length
    }
    return count
}

private fun previewLines(value: String, prefix: String): List<String> {
    val lines = normalizeLineEndings(value).split("\n")
    val shown = lines.take(6).map { line ->
        val truncated = if (line.length > 240) line.substring(0, 240) + "..." else line
        "$prefix$truncated"
    }
    return if (lines.size > shown.size) shown + "$prefix..." else shown
}

private fun buildDiffPreview(oldString: String, newString: String): String {
    val lines = mutableListOf<String>()
    lines.addAll(previewLines(oldString, "-"))
    lines.addAll(previewLines(newString, "+"))
    return lines.joinToString("\n")
}

class EditTool(metadata: ToolMetadata, private val workDir: Path) : BaseToolDefinition(metadata) {
    override val executionMode: ToolExecutionMode get() = ToolExecutionMode.PARALLEL

    override fun parameterType(): Class<*> = EditFileParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val pathStr = args["path"] as? String ?: return@withContext errorResult(toolCallId, name, "Error: missing 'path' parameter")
        val oldString = args["oldString"] as? String ?: return@withContext errorResult(toolCallId, name, "Error: missing 'oldString' parameter")
        val newString = args["newString"] as? String ?: return@withContext errorResult(toolCallId, name, "Error: missing 'newString' parameter")
        val replaceAll = args["replaceAll"] as? Boolean

        if (oldString == newString) return@withContext errorResult(toolCallId, name, "No changes to apply: oldString and newString are identical.")
        if (oldString.isEmpty()) return@withContext errorResult(toolCallId, name, "oldString must not be empty. Use write to create or overwrite a file.")

        val file = workDir.resolveSafe(pathStr)
        if (!file.exists()) return@withContext errorResult(toolCallId, name, "Error: file not found: $pathStr")

        // Read file content and store original bytes for stale content detection
        val rawContent = file.readText()
        val expectedBytes = rawContent.toByteArray(Charsets.UTF_8)
        val bomInfo = splitBom(rawContent)
        val ending = detectLineEnding(bomInfo.text)
        val normalizedOld = convertToLineEnding(oldString, ending)
        val normalizedNew = convertToLineEnding(newString, ending)
        val replacements = countOccurrences(bomInfo.text, normalizedOld)

        if (replacements == 0) return@withContext errorResult(toolCallId, name, "Could not find oldString in the file. It must match exactly, including whitespace and indentation.")
        if (replacements > 1 && replaceAll != true) return@withContext errorResult(toolCallId, name, "Found multiple exact matches for oldString. Provide more surrounding context or set replaceAll to true.")

        val replaced = if (replaceAll == true) {
            bomInfo.text.replace(normalizedOld, normalizedNew)
        } else {
            bomInfo.text.replaceFirst(normalizedOld, normalizedNew)
        }
        val finalContent = joinBom(replaced, bomInfo.bom)

        // Write-if-unchanged: verify file hasn't been modified since we read it
        val currentBytes = file.readBytes()
        if (!currentBytes.contentEquals(expectedBytes)) {
            return@withContext errorResult(toolCallId, name, "File changed after reading. Read it again before editing.")
        }
        file.writeText(finalContent)

        val diffPreview = buildDiffPreview(oldString, newString)
        val output = buildString {
            appendLine("Edited file successfully: $pathStr")
            appendLine("Replacements: $replacements")
            appendLine("```diff")
            append(diffPreview)
            appendLine()
            append("```")
        }
        ToolResult(content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = output)))
    }

}