package com.easy.easyai.core.message

import com.easy.easyai.core.message.ToolResultGuard.DEFAULT_MAX_TOOL_RESULT_CHARS
import com.easy.easyai.core.message.ToolResultGuard.guard
import com.easy.easyai.core.message.ToolResultGuard.guardEntry
import com.easy.easyai.core.model.ToolResultEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * Hard-limit guard for tool result payloads at their generation points.
 *
 * A single oversized tool result (observed: 527K chars, ~150K tokens) can consume most of
 * the context window, trigger premature compaction, and be re-generated after compaction
 * when the model re-runs the tool.
 *
 * Since the oversized results are spilled to the system temp dir (`java.io.tmpdir`/easyai-tool-output)
 * and replaced with a structured pointer notice (path, line count, access patterns, cleanup
 * warning, char-budget sample), the persisted transcript and LLM requests no longer carry the
 * large payload. Spill is idempotent (same toolCallId + same content -> same file, write skipped
 * when already present); on write failure the legacy head+tail truncation is used as fallback so
 * request construction never breaks. Files are cleaned up by the OS (systemd-tmpfiles on Linux,
 * periodic cleanup on macOS); the notice instructs the model to RE-RUN the tool when the file
 * has been removed.
 */
object ToolResultGuard {

    /** System property that overrides [DEFAULT_MAX_TOOL_RESULT_CHARS]. */
    const val MAX_TOOL_RESULT_CHARS_PROPERTY = "easyai.tool.maxResultChars"

    /**
     * Default maximum characters kept for one tool result in the transcript.
     * Roughly 29K tokens under O200K-style tokenizers, leaving room for the rest of
     * the context inside a 200K-token window.
     */
    const val DEFAULT_MAX_TOOL_RESULT_CHARS = 100_000

    /** Fraction of the budget given to the head of the result; the remainder goes to the tail. */
    private const val HEAD_RATIO = 0.6

    /** Char budget for each side of the notice sample (head / tail). */
    private const val SAMPLE_CHARS = 1_000

    /** Directory name under the system temp dir where spilled outputs live. */
    private const val SPILL_DIR_NAME = "easyai-tool-output"

    private val logger = LoggerFactory.getLogger(ToolResultGuard::class.java)

    /** Effective limit, resolved once from the system property at class-load time. */
    val maxToolResultChars: Int =
        System.getProperty(MAX_TOOL_RESULT_CHARS_PROPERTY)?.toIntOrNull()
            ?.takeIf { it > 0 } ?: DEFAULT_MAX_TOOL_RESULT_CHARS

    /** System temp dir (java.io.tmpdir): Linux `/tmp`, macOS `/var/folders/.../T/`, Windows `%TEMP%`. */
    private val tmpDir: Path by lazy {
        Path.of(System.getProperty("java.io.tmpdir") ?: "/tmp")
    }

    /** Test hook: overrides the spill directory; null uses <tmpdir>/easyai-tool-output. */
    internal var spillDirOverride: Path? = null

    private val spillDir: Path
        get() = spillDirOverride ?: tmpDir.resolve(SPILL_DIR_NAME)

    /**
     * Result of [guard]: the possibly truncated text plus whether truncation occurred.
     */
    data class GuardedResult(val text: String, val truncated: Boolean)

    /**
     * Truncate [result] to at most [maxChars] (marker included in the budget) when it
     * exceeds the limit, keeping head + tail.
     * The marker is deterministic: the same input always produces the same output.
     * Kept as the fallback path for [guardEntry] and for callers that only need plain text.
     */
    @JvmStatic
    fun guard(result: String, maxChars: Int = maxToolResultChars): GuardedResult {
        if (result.length <= maxChars) return GuardedResult(result, false)
        // Defensive: a misconfigured non-positive limit must never break request
        // construction with substring exceptions.
        if (maxChars <= 0) return GuardedResult("", true)
        val marker = "\n... [output truncated: ${result.length - maxChars} chars omitted] ...\n"
        val budget = (maxChars - marker.length).coerceAtLeast(0)
        val headChars = (budget * HEAD_RATIO).toInt()
        val tailChars = budget - headChars
        val text = result.substring(0, headChars) + marker + result.substring(result.length - tailChars)
        return GuardedResult(text, true)
    }

    /**
     * Apply the guard to a [ToolResultEntry]: spill the oversized result to the temp dir and
     * replace it with a pointer notice. Falls back to [guard] truncation when spilling fails.
     * Returns the entry unchanged when no spill/truncation is needed.
     */
    @JvmStatic
    suspend fun guardEntry(entry: ToolResultEntry, maxChars: Int = maxToolResultChars): ToolResultEntry {
        if (entry.result.length <= maxChars) return entry
        if (maxChars <= 0) return entry.copy(result = "", truncated = true)
        return try {
            entry.copy(result = spill(entry), truncated = true)
        } catch (e: Exception) {
            logger.warn("Tool result spill failed for {} ({} chars), falling back to truncation: {}",
                entry.toolCallId, entry.result.length, e.message)
            val guarded = guard(entry.result, maxChars)
            entry.copy(result = guarded.text, truncated = true)
        }
    }

    private suspend fun spill(entry: ToolResultEntry): String = withContext(Dispatchers.IO) {
        val dir = spillDir
        Files.createDirectories(dir)
        val result = entry.result
        val bytes = result.toByteArray(Charsets.UTF_8)
        val file = dir.resolve(fileName(entry.toolCallId, sha256(result)))
        // Idempotent: same toolCallId + same content -> same file; skip when already present
        // with matching size (history replay / repeated requests must not rewrite).
        if (!Files.exists(file) || Files.size(file) != bytes.size.toLong()) {
            Files.write(file, bytes)
        }
        logger.info("Spilled oversized tool result {} ({} chars) to {}", entry.toolCallId, result.length, file)
        buildNotice(entry, file)
    }

    private fun fileName(toolCallId: String, hash: String): String =
        "${sanitize(toolCallId)}_${hash.take(8)}.txt"

    private fun sanitize(id: String): String =
        id.map { c -> if (c.isLetterOrDigit() || c == '_' || c == '-') c else '_' }.joinToString("")

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private fun buildNotice(entry: ToolResultEntry, file: Path): String {
        val result = entry.result
        val lines = result.lines().size
        // Char-budget sample (not fixed line count): a single-line oversized payload
        // (compact JSON/CSV/base64) would make a line-based sample equal the full content.
        // The template is left unindented so trimIndent does not shift the multi-line samples.
        val headRaw = result.take(SAMPLE_CHARS)
        val headSample = headRaw.substringBeforeLast('\n').ifEmpty { headRaw }
        val tailRaw = result.takeLast(SAMPLE_CHARS)
        val tailSample = tailRaw.substringAfter('\n', missingDelimiterValue = tailRaw)
        return """
[Output too large: ${result.length} chars → saved to $file ($lines lines)]

The file may be removed by the system later. If it no longer exists or reading fails,
RE-RUN the tool to regenerate the data.

Access patterns:
- read tool: offset=0&limit=500 for LINE-based slicing (1-based line numbers in output).
  NOTE: offset/limit are line-based; for few-line files (e.g., a single-line JSON/CSV) line
  slicing cannot narrow the data down — use grep -o or python instead
- grep '<pattern>' $file for targeted lookup
- bash + python for full computation (calc cannot read files)

Sample of the data (first ~$SAMPLE_CHARS chars / last ~$SAMPLE_CHARS chars):
--- head ---
$headSample
--- tail ---
$tailSample
""".trimIndent()
    }
}
