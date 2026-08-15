package com.easy.easyai.tools.calc

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.time.Duration.Companion.seconds

/**
 * Parameters for [ScriptCalcTool].
 */
data class ScriptCalcParams(
    /** Groovy script to execute in-memory for numerical or date/time calculations. */
    val script: String,
    /**
     * Optional absolute path to a data file whose content will be read by the host
     * (not by the Groovy sandbox) and injected into the script as the `__data__` variable.
     *
     * The file is read line-by-line; `__data__` is a `List<String>` where each element
     * is one line (header included). The script can parse it as needed.
     *
     * Security: path is validated by the host — no symlinks, no path traversal,
     * size capped at [MAX_FILE_BYTES]. The Groovy sandbox itself still has no file I/O.
     */
    val filePath: String? = null
)

/**
 * Executes Groovy scripts in-memory for safe numerical and date/time calculations.
 *
 * The script runs inside a sandboxed [groovy.lang.GroovyShell] with restricted imports
 * (no file I/O, no network, no process spawning) and a hard timeout, so it is strictly
 * safer than the bash tool for any computation task.
 */
class ScriptCalcTool(metadata: ToolMetadata) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = ScriptCalcParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val script = args["script"] as? String
        if (script.isNullOrBlank()) {
            return@withContext errorResult("Error: 'script' parameter is required and must not be empty")
        }

        // --- Optional file read (host-side, sandbox has no file I/O) ---
        val filePath = args["filePath"] as? String
        var fileData: List<String>? = null
        if (!filePath.isNullOrBlank()) {
            val validationError = validateFilePath(filePath)
            if (validationError != null) {
                return@withContext errorResult(validationError)
            }
            try {
                val file = File(filePath)
                if (file.length() > MAX_FILE_BYTES) {
                    return@withContext errorResult(
                        "Error: file '${file.name}' is ${file.length()} bytes, exceeds the ${MAX_FILE_BYTES / 1024}KB limit. " +
                        "For large files, use the bash tool to run a Python or JavaScript script instead."
                    )
                }
                fileData = file.readLines()
            } catch (e: Exception) {
                logger.warn("Failed to read file {}: {}", filePath, e.message)
                return@withContext errorResult("Error: cannot read file '$filePath': ${e.message}")
            }
        }

        onUpdate(ToolUpdate.Progress("Calculating…"))

        try {
            val result = withTimeout(TIMEOUT_SECONDS.seconds) {
                val shell = GroovySandbox.createSecureShell()
                val outputCapture = StringWriter()
                shell.context.setVariable("out", PrintWriter(outputCapture))
                // Inject file data as __data__ variable if a file was provided
                if (fileData != null) {
                    shell.context.setVariable("__data__", fileData)
                }
                val evalResult = shell.evaluate(script)
                val stdout = outputCapture.toString().trimEnd('\n')
                // Prefer stdout (println output) over the last expression value
                stdout.ifEmpty { evalResult?.toString() ?: "null" }
            }
            ToolResult(content = listOf(TextContent(result)))
        } catch (_: TimeoutCancellationException) {
            errorResult("Script execution timed out after ${TIMEOUT_SECONDS}s")
        } catch (e: org.codehaus.groovy.control.CompilationFailedException) {
            logger.debug("Script compilation error: {}", e.message)
            // CompilationFailedException.message contains line/column details — keep full text
            val detail = e.message?.trim() ?: "unknown compilation error"
            errorResult("Script syntax error:\n$detail")
        } catch (e: groovy.lang.GroovyRuntimeException) {
            logger.debug("Script runtime error: {}", e.message)
            errorResult("Script runtime error: ${e.message?.lines()?.firstOrNull() ?: e.message}")
        } catch (e: SecurityException) {
            logger.warn("Script security violation: {}", e.message)
            errorResult("Script security violation: ${e.message}")
        } catch (e: Exception) {
            logger.warn("Script execution failed: {}", e.message)
            errorResult("Script execution error: ${e.message}")
        }
    }

    /**
     * Validates [path] for safe file access. Returns an error message if invalid, or null if OK.
     *
     * Checks:
     * 1. Must be an absolute path
     * 2. No path traversal (no ".." segments after normalization)
     * 3. Must not be a symlink (prevents symlink-to-sensitive-file attacks)
     * 4. Must be a regular file that exists
     */
    private fun validateFilePath(path: String): String? {
        if (!path.startsWith("/")) {
            return "Error: 'filePath' must be an absolute path, got: $path"
        }
        // Normalize to resolve any ".." or "." segments
        val normalized = File(path).canonicalPath
        if (normalized != File(path).absolutePath) {
            // canonicalPath differs → path contained ".." or "." segments
            return "Error: 'filePath' contains path traversal sequences, use the canonical path: $normalized"
        }
        val file = File(normalized)
        if (java.nio.file.Files.isSymbolicLink(file.toPath())) {
            return "Error: 'filePath' must not be a symbolic link: $path"
        }
        if (!file.exists()) {
            return "Error: file not found: $path"
        }
        if (!file.isFile) {
            return "Error: 'filePath' must be a regular file, not a directory: $path"
        }
        return null
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10
        /** Max file size: 512KB — enough for CSV/TSV data, prevents memory exhaustion. */
        private const val MAX_FILE_BYTES = 512L * 1024L
    }
}
