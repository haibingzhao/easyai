package com.easy.easyai.core.memory

import com.easy.easyai.core.agent.AgentContext
import org.slf4j.LoggerFactory
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.io.path.*

/**
 * File-based implementation of [MemoryStore].
 *
 * Storage layout:
 * ```
 * {root}/
 * ├── MEMORY.md              # Index file
 * ├── user/                  # USER type entries
 * ├── feedback/              # FEEDBACK type entries
 * ├── project/               # PROJECT type entries
 * └── reference/             # REFERENCE type entries
 * ```
 *
 * Each entry is a Markdown file with YAML frontmatter:
 * ```
 * ---
 * name: entry_name
 * description: One-line summary
 * type: feedback
 * created: 2026-06-28
 * updated: 2026-06-28
 * ---
 * (Markdown body)
 * ```
 *
 * Thread safety: concurrent reads are safe; writes use atomic temp+rename.
 *
 * @param globalRoot Absolute path to global memory root (default: `~/.easyai/memory`).
 * @param projectRelativePath project relative Path.
 */
internal class FileMemoryStore(
    private val globalRoot: Path,
    private val projectRelativePath: String
) : MemoryStore {

    private val logger = LoggerFactory.getLogger(javaClass)
    private var globalInitialized = false
    private var projectInitialized = false

    companion object {
        private const val INDEX_FILE = "MEMORY.md"
        private const val FRONTMATTER_DELIMITER = "---"
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
    }

    // ── Scope resolution ───────────────────────────────────────────────

    private fun resolveRoot(scope: MemoryScope, agentContext: AgentContext? = null): Path? {
        val scopePath = when (scope) {
            MemoryScope.GLOBAL -> globalRoot
            MemoryScope.PROJECT -> agentContext?.projectPath?.resolve(projectRelativePath)
        }
        if(scope == MemoryScope.PROJECT && !projectInitialized){
            initialize(scopePath)
            projectInitialized = true
        } else if(scope == MemoryScope.GLOBAL && !globalInitialized) {
            initialize(scopePath)
            globalInitialized = true
        }
        return scopePath
    }

    // ── Directory initialization ───────────────────────────────────────

    fun initialize(scopePath : Path?) {
        val root = scopePath ?: return
        try {
            root.createDirectories()
            MemoryType.entries.forEach { type ->
                root.resolve(type.dirName).createDirectories()
            }
            logger.trace("Memory directories initialized at {}", root)
        } catch (e: IOException) {
            logger.warn("Failed to initialize memory directories at {}: {}", root, e.message)
        }
    }

    // ── loadAll ────────────────────────────────────────────────────────

    override suspend fun loadAll(
        agentContext: AgentContext,
        scope: MemoryScope,
        totalCharLimit: Int,
        perFileCharLimit: Int
    ): String {
        val root = resolveRoot(scope, agentContext) ?: return ""
        val entries = scanAllEntries(root)
        if (entries.isEmpty()) return ""

        val indexSection = buildIndexSection(entries)
        val indexLen = indexSection.length
        val contentBudget = (totalCharLimit - indexLen).coerceAtLeast(0)
        val contentSection = if (contentBudget > 0) {
            buildContentSection(entries, contentBudget, perFileCharLimit)
        } else null

        return buildString {
            appendLine(indexSection)
            if (contentSection != null) {
                appendLine()
                append(contentSection)
            } else {
                appendLine()
                appendLine("Memory index shown. Use memory_read to read full content of specific entries.")
            }
        }
    }

    private fun buildIndexSection(entries: List<MemoryEntry>): String = buildString {
        appendLine("## Memory Index")
        entries
            .sortedBy { it.type.dirName }
            .forEach { entry ->
                appendLine("- [${entry.name}](${entry.path}) — ${entry.description}")
            }
    }

    private fun buildContentSection(
        entries: List<MemoryEntry>,
        totalCharLimit: Int,
        perFileCharLimit: Int
    ): String? {
        val sb = StringBuilder()
        sb.appendLine("## Memory Content")
        var totalChars = sb.length

        for ((index, entry) in entries.withIndex()) {
            val truncated = if (entry.content.length > perFileCharLimit) {
                entry.content.take(perFileCharLimit) + "\n...[truncated]"
            } else {
                entry.content
            }
            val block = "[${entry.path}]\n$truncated\n\n"
            if (totalChars + block.length > totalCharLimit) {
                // Return partial content if we have accumulated some; null if first entry exceeds
                return if (index == 0) null else sb.toString()
            }
            sb.append(block)
            totalChars += block.length
        }
        return sb.toString()
    }

    // ── search ─────────────────────────────────────────────────────────

    override suspend fun search(agentContext: AgentContext, query: String, scope: MemoryScope, limit: Int): List<MemoryEntry> {
        val root = resolveRoot(scope, agentContext) ?: return emptyList()
        val lowerQuery = query.lowercase()
        return scanAllEntries(root)
            .filter { entry ->
                entry.name.lowercase().contains(lowerQuery) ||
                    entry.description.lowercase().contains(lowerQuery) ||
                    entry.content.lowercase().contains(lowerQuery)
            }
            .take(limit)
    }

    // ── write ──────────────────────────────────────────────────────────

    override suspend fun write(agentContext: AgentContext, entry: MemoryEntry, scope: MemoryScope): Path {
        val root = resolveRoot(scope, agentContext)
            ?: throw IllegalStateException("Cannot write memory: no root path for scope $scope")

        val filePath = root.resolve(entry.path).normalize()
        if (!filePath.startsWith(root.normalize())) {
            throw IllegalArgumentException("Path traversal detected in memory write: ${entry.path}")
        }
        // Ensure parent directory exists (type subdirectory may not exist yet)
        filePath.parent.createDirectories()
        val fileContent = buildFileContent(entry)

        // Atomic write: write to temp file, then rename
        val tempFile = Files.createTempFile(filePath.parent, ".memory-", ".tmp")
        try {
            tempFile.writeText(fileContent)
            try {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, filePath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tempFile.deleteIfExists()
            throw e
        }

        // Refresh index after write
        refreshIndex(agentContext, scope)

        logger.debug("Memory entry written: {}", filePath)
        return filePath
    }

    private fun buildFileContent(entry: MemoryEntry): String = buildString {
        appendLine(FRONTMATTER_DELIMITER)
        appendLine("name: ${quoteYamlValue(entry.name)}")
        appendLine("description: ${quoteYamlValue(entry.description)}")
        appendLine("type: ${entry.type.dirName}")
        if (entry.keywords.isNotEmpty()) {
            appendLine("keywords: ${entry.keywords.joinToString(", ") { quoteYamlValue(it) }}")
        }
        appendLine("created: ${(entry.created ?: LocalDate.now()).format(DATE_FMT)}")
        appendLine("updated: ${(entry.updated ?: LocalDate.now()).format(DATE_FMT)}")
        appendLine(FRONTMATTER_DELIMITER)
        appendLine()
        append(entry.content)
    }

    private fun quoteYamlValue(value: String): String {
        // Sanitize newlines to prevent YAML format corruption
        val sanitized = value.replace("\n", " ").replace("\r", "")
        val needsQuoting = sanitized.any { it in ":#\"{}[]&*!|>%@" }
        return if (needsQuoting) {
            "\"${sanitized.replace("\\", "\\\\").replace("\"", "\\\"")}\"";
        } else {
            sanitized
        }
    }

    // ── deleteAll ───────────────────────────────────────────────────────

    override suspend fun deleteAll(agentContext: AgentContext, scope: MemoryScope): Int {
        val root = resolveRoot(scope, agentContext) ?: return 0
        if (!root.exists()) return 0
        var count = 0
        MemoryType.entries.forEach { type ->
            val typeDir = root.resolve(type.dirName)
            if (typeDir.isDirectory()) {
                typeDir.listDirectoryEntries("*.md").forEach { file ->
                    if (file.deleteIfExists()) count++
                }
            }
        }
        if (count > 0) {
            refreshIndex(agentContext, scope)
            logger.debug("Deleted all {} memory entries for scope {}", count, scope)
        }
        return count
    }

    // ── read ───────────────────────────────────────────────────────────

    override suspend fun read(agentContext: AgentContext, path: String, scope: MemoryScope): String? {
        val root = resolveRoot(scope, agentContext) ?: return null
        val normalizedRoot = root.normalize()
        val filePath = root.resolve(path).normalize()
        if (!filePath.startsWith(normalizedRoot)) {
            logger.warn("Path traversal attempt blocked: {}", path)
            return null
        }
        return if (filePath.exists() && !filePath.isDirectory()) {
            filePath.readText()
        } else {
            null
        }
    }

    // ── delete ─────────────────────────────────────────────────────────

    override suspend fun delete(agentContext: AgentContext, path: String, scope: MemoryScope): Boolean {
        val root = resolveRoot(scope, agentContext) ?: return false
        val normalizedRoot = root.normalize()
        val filePath = root.resolve(path).normalize()
        if (!filePath.startsWith(normalizedRoot)) {
            logger.warn("Path traversal attempt blocked: {}", path)
            return false
        }
        val deleted = filePath.deleteIfExists()
        if (deleted) {
            refreshIndex(agentContext, scope)
            logger.debug("Memory entry deleted: {}", filePath)
        }
        return deleted
    }

    // ── list ───────────────────────────────────────────────────────────

    override suspend fun list(agentContext: AgentContext, scope: MemoryScope, type: MemoryType?): List<MemoryEntry> {
        val root = resolveRoot(scope, agentContext) ?: return emptyList()
        val entries = scanAllEntries(root)
        return if (type != null) entries.filter { it.type == type } else entries
    }

    // ── exists ─────────────────────────────────────────────────────────

    override suspend fun exists(agentContext: AgentContext, name: String, scope: MemoryScope): Boolean {
        val root = resolveRoot(scope, agentContext) ?: return false
        return MemoryType.entries.any { type ->
            root.resolve(type.dirName).resolve("$name.md").exists()
        }
    }

    // ── findByName ─────────────────────────────────────────────────────

    override suspend fun findByName(agentContext: AgentContext, name: String, scope: MemoryScope): MemoryEntry? {
        val root = resolveRoot(scope, agentContext) ?: return null
        for (type in MemoryType.entries) {
            val filePath = root.resolve(type.dirName).resolve("$name.md")
            if (filePath.exists() && !filePath.isDirectory()) {
                return parseFile(filePath, type)
            }
        }
        return null
    }

    // ── refreshIndex ───────────────────────────────────────────────────

    override suspend fun refreshIndex(agentContext: AgentContext, scope: MemoryScope) {
        val root = resolveRoot(scope, agentContext) ?: return
        val entries = scanAllEntries(root)
        val indexContent = generateIndexContent(entries)
        val indexPath = root.resolve(INDEX_FILE)

        val tempFile = Files.createTempFile(root, ".index-", ".tmp")
        try {
            tempFile.writeText(indexContent)
            try {
                Files.move(tempFile, indexPath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, indexPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } catch (e: Exception) {
            tempFile.deleteIfExists()
            throw e
        }
    }

    private fun generateIndexContent(entries: List<MemoryEntry>): String = buildString {
        appendLine("# Memory Index")
        appendLine()
        MemoryType.entries.forEach { type ->
            val typeEntries = entries.filter { it.type == type }
            if (typeEntries.isNotEmpty()) {
                appendLine("## ${type.dirName.replaceFirstChar { it.uppercase() }}")
                typeEntries.sortedBy { it.name }.forEach { entry ->
                    appendLine("- [${entry.description}](${entry.path}) — ${entry.name}")
                }
                appendLine()
            }
        }
    }

    // ── File scanning & parsing ────────────────────────────────────────

    /**
     * Scan all type directories for .md files and parse them into [MemoryEntry] list.
     */
    private fun scanAllEntries(root: Path): List<MemoryEntry> {
        if (!root.exists()) return emptyList()
        return MemoryType.entries.flatMap { type ->
            val typeDir = root.resolve(type.dirName)
            if (!typeDir.isDirectory()) return@flatMap emptyList()
            typeDir.listDirectoryEntries("*.md")
                .filter { it.exists() && !it.isDirectory() }
                .mapNotNull { file ->
                    try {
                        parseFile(file, type)
                    } catch (e: Exception) {
                        logger.warn("Failed to parse memory file {}: {}", file, e.message)
                        null
                    }
                }
        }
    }

    /**
     * Parse a single memory file with YAML frontmatter into a [MemoryEntry].
     */
    private fun parseFile(file: Path, type: MemoryType): MemoryEntry {
        val text = file.readText()
        val (frontmatter, body) = splitFrontmatter(text)
        val meta = parseFrontmatter(frontmatter)

        val name = meta["name"] ?: file.fileName.toString().removeSuffix(".md")
        val description = meta["description"] ?: ""
        val resolvedType = meta["type"]?.let { MemoryType.fromDirName(it) } ?: type
        val keywords = meta["keywords"]?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()
        val created = meta["created"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }
        val updated = meta["updated"]?.let { runCatching { LocalDate.parse(it, DATE_FMT) }.getOrNull() }

        val relativePath = "${resolvedType.dirName}/${file.fileName}"

        return MemoryEntry(
            name = name,
            description = description,
            type = resolvedType,
            content = body.trim(),
            path = relativePath,
            keywords = keywords,
            created = created,
            updated = updated
        )
    }

    /**
     * Split file text into frontmatter (between --- delimiters) and body.
     */
    private fun splitFrontmatter(text: String): Pair<String, String> {
        val lines = text.lines()
        if (lines.isEmpty() || lines[0].trim() != FRONTMATTER_DELIMITER) {
            return "" to text
        }
        val endIndex = lines.drop(1).indexOfFirst { it.trim() == FRONTMATTER_DELIMITER }
        if (endIndex < 0) return "" to text

        // endIndex is relative to drop(1) list, so original index is endIndex + 1
        val frontmatter = lines.subList(1, endIndex + 1).joinToString("\n")
        val body = lines.subList(endIndex + 2, lines.size).joinToString("\n")
        return frontmatter to body
    }

    /**
     * Parse simple YAML frontmatter key-value pairs.
     * Supports only flat `key: value` format (no nested structures).
     */
    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
        return frontmatter.lines()
            .filter { it.contains(':') }
            .associate { line ->
                val colonIndex = line.indexOf(':')
                val key = line.substring(0, colonIndex).trim()
                var value = line.substring(colonIndex + 1).trim()
                // Strip surrounding quotes if present
                if (value.length >= 2 &&
                    ((value.startsWith("\"") && value.endsWith("\"")) ||
                     (value.startsWith("'") && value.endsWith("'")))
                ) {
                    value = value.substring(1, value.length - 1)
                        .replace("\\\"", "\"")
                }
                key to value
            }
    }
}
