package com.easy.easyai.core.prompt

import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isRegularFile
import kotlin.io.path.readText

/**
 * Loads project-level instructions from AGENTS.md files.
 * File-driven, no database needed. Follows OpenCode's discovery pattern:
 * searches for AGENTS.md / CLAUDE.md / CONTEXT.md in the project root (first match wins),
 * and supports sub-directory resolution when reading files.
 */
object InstructionsLoader {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Max file size in characters (8KB). Files exceeding this are truncated. */
    private const val MAX_FILE_SIZE = 8 * 1024

    /** Instruction file names in priority order (first match wins, like OpenCode). */
    private val instructionFileNames = listOf("AGENTS.md", "CLAUDE.md", "CONTEXT.md")

    /**
     * Load project-level instructions.
     * Searches [projectPath] for AGENTS.md (or CLAUDE.md, CONTEXT.md); first match wins.
     *
     * @param projectPath the project root directory, or null if unknown
     * @return list of loaded instructions (0 or 1 element for project-level)
     */
    fun load(projectPath: Path?): List<InstructionInfo> {
        if (projectPath == null) return emptyList()
        for (fileName in instructionFileNames) {
            val file = projectPath.resolve(fileName)
            if (file.exists() && file.isRegularFile()) {
                val content = readFileSafely(file)
                return listOf(
                    InstructionInfo(
                        name = fileName,
                        content = content,
                        source = InstructionSource.PROJECT,
                        location = file,
                    )
                )
            }
        }
        return emptyList()
    }

    /**
     * Format instructions for system prompt injection.
     * Returns null if the list is empty so callers can filter blank segments.
     */
    fun formatForPrompt(instructions: List<InstructionInfo>): String? {
        if (instructions.isEmpty()) return null
        return buildString {
            appendLine("## Project Instructions")
            appendLine("The following instructions are loaded from the project workspace.")
            appendLine("Follow them carefully as they define project-specific conventions and rules.")
            appendLine()
            instructions.forEach { info ->
                appendLine("### From: ${info.location?.fileName ?: info.name}")
                appendLine(info.content)
                appendLine()
            }
        }.trimEnd()
    }

    /**
     * Resolve sub-directory instructions for a file read operation.
     * Walks upward from [fileDir] to [projectRoot], collecting AGENTS.md files
     * that haven't been loaded yet (deduplication via [alreadyLoaded] set).
     */
    fun resolveForFileRead(
        fileDir: Path,
        projectRoot: Path,
        alreadyLoaded: Set<Path>,
    ): List<InstructionInfo> {
        val results = mutableListOf<InstructionInfo>()
        val seen = alreadyLoaded.toMutableSet()
        var current: Path? = fileDir.toAbsolutePath().normalize()
        while (current != null && current.startsWith(projectRoot) && current != projectRoot) {
            for (fileName in instructionFileNames) {
                val candidate = current.resolve(fileName)
                if (candidate.exists() && candidate.isRegularFile()
                    && seen.add(candidate.toAbsolutePath())
                ) {
                    results.add(
                        InstructionInfo(
                            name = fileName,
                            content = readFileSafely(candidate),
                            source = InstructionSource.SUBDIR,
                            location = candidate,
                        )
                    )
                    break
                }
            }
            current = current.parent
        }
        return results
    }

    private fun readFileSafely(file: Path): String {
        return try {
            val content = file.readText()
            if (content.length > MAX_FILE_SIZE) {
                logger.warn("Instruction file {} exceeds {} chars, truncating", file, MAX_FILE_SIZE)
                content.take(MAX_FILE_SIZE) + "\n\n[...truncated...]"
            } else {
                content
            }
        } catch (e: Exception) {
            logger.warn("Failed to read instruction file {}: {}", file, e.message)
            ""
        }
    }
}

/** Metadata for a single loaded instruction source. */
data class InstructionInfo(
    val name: String,
    val content: String,
    val source: InstructionSource,
    val location: Path? = null,
)

/** Where the instruction was loaded from. */
enum class InstructionSource { PROJECT, SUBDIR }
