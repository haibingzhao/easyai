package com.easy.easyai.tools.calc

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.message.ToolResultGuard
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import groovy.lang.GroovyShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import kotlin.time.Duration
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
     * size capped at [MAX_FILE_BYTES]. In an unattended run the path is additionally
     * confined to [ToolResultGuard.spillDirectory] and the bound project, because no
     * user is present to approve an arbitrary file read. The Groovy sandbox itself
     * still has no file I/O.
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
            val boundaryError = validateUnattendedPath(filePath, agentContext)
            if (boundaryError != null) {
                return@withContext errorResult(boundaryError)
            }
            try {
                val file = File(filePath)
                if (file.length() > MAX_FILE_BYTES) {
                    return@withContext errorResult(
                        "Error: file '${file.name}' is ${file.length()} bytes, exceeds the ${MAX_FILE_BYTES / 1024}KB limit. " +
                        "Request a smaller slice from the data source, or split the file."
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
            val shell = GroovySandbox.createSecureShell()
            val outputCapture = StringWriter()
            shell.context.setVariable("out", PrintWriter(outputCapture))
            // Inject file data as __data__ variable if a file was provided
            if (fileData != null) {
                shell.context.setVariable("__data__", fileData)
            }
            val evalResult = evaluateWithTimeout(shell, script, TIMEOUT_SECONDS.seconds)
            val stdout = outputCapture.toString().trimEnd('\n')
            // Prefer stdout (println output) over the last expression value
            val result = stdout.ifEmpty { evalResult?.toString() ?: "null" }
            ToolResult(content = listOf(TextContent(result)))
        } catch (_: ScriptTimeoutException) {
            errorResult("Script execution timed out after ${TIMEOUT_SECONDS}s")
        } catch (_: ScriptStepLimitException) {
            errorResult(
                "Script exceeded the $MAX_STEPS-step limit: likely an infinite loop. " +
                    "Fix the loop condition or reduce the number of iterations."
            )
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
     * Evaluates [script] on a dedicated daemon thread with a hard wall-clock timeout.
     *
     * kotlinx `withTimeout` cannot enforce this limit: `shell.evaluate` is a plain
     * blocking call, the `withTimeout` block contains no suspension point, so the
     * cancellation is only delivered after `evaluate` returns — a runaway script
     * (e.g. `while (true)`) would hang the tool forever. `Thread.join(timeout)`
     * provides a true hard timeout independent of the script's behavior; on expiry
     * the worker is interrupted and abandoned (daemon, so it cannot block JVM exit).
     *
     * Pure-CPU loops that ignore interrupts are additionally bounded by the
     * [ScriptLoopGuard] step budget (see [LoopInstrumentationCustomizer] in
     * [GroovySandbox]), which aborts them deterministically well before the
     * wall-clock timeout.
     *
     * Internal visibility (not private) so tests can verify the join(timeout)
     * mechanism with a short timeout instead of waiting the full 10s.
     */
    internal fun evaluateWithTimeout(shell: GroovyShell, script: String, timeout: Duration): Any? {
        var result: Any? = null
        var thrown: Throwable? = null
        val worker = Thread({
            ScriptLoopGuard.arm(MAX_STEPS)
            try {
                result = shell.evaluate(script)
            } catch (t: Throwable) {
                thrown = t
            } finally {
                ScriptLoopGuard.disarm()
            }
        }, "calc-script-worker")
        worker.isDaemon = true
        worker.start()
        worker.join(timeout.inWholeMilliseconds)
        if (worker.isAlive) {
            worker.interrupt()
            logger.warn("Calc script exceeded {}ms timeout, worker interrupted", timeout.inWholeMilliseconds)
            throw ScriptTimeoutException()
        }
        // ScriptStepLimitException and other Exceptions propagate as-is; JVM Errors
        // (e.g. StackOverflowError from unbounded recursion) are wrapped so the
        // catch blocks in doExecute can turn them into a normal tool error.
        thrown?.let {
            if (it is Exception) throw it
            throw RuntimeException("Script error: ${it.javaClass.simpleName}", it)
        }
        return result
    }

    /**
     * Signals that the script exceeded its wall-clock timeout.
     *
     * A class rather than an `object`: [RuntimeException] is `Serializable`, and a
     * Kotlin `object` that is serializable requires `readResolve` to keep its
     * identity — throwing a fresh instance is simpler and matches
     * [ScriptStepLimitException].
     */
    internal class ScriptTimeoutException : RuntimeException("script timeout")

    /**
     * Validates [path] for safe file access. Returns an error message if invalid, or null if OK.
     *
     * Checks:
     * 1. Must be an absolute path
     * 2. No `..` segments (rejected explicitly: comparing canonical and absolute paths would also
     *    reject legitimate paths under a symlinked system temp dir, e.g. `/var` on macOS)
     * 3. Must not be a symlink (prevents symlink-to-sensitive-file attacks)
     * 4. Must be a regular file that exists
     */
    private fun validateFilePath(path: String): String? {
        if (!path.startsWith("/")) {
            return "Error: 'filePath' must be an absolute path, got: $path"
        }
        if (File(path).toPath().any { it.toString() == ".." }) {
            return "Error: 'filePath' must not contain '..' segments, use the canonical path: " +
                File(path).canonicalPath
        }
        val file = File(path)
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

    /**
     * An unattended run has no user to approve a read, so its host-side file access is confined to
     * the places it legitimately needs: tool results spilled by [ToolResultGuard] and files inside
     * the bound project (which the `read` tool could already open in the same run). Without this,
     * model output driven by untrusted watched content could pull arbitrary local files into a
     * finding that is then pushed to the user.
     */
    private fun validateUnattendedPath(path: String, agentContext: AgentContext): String? {
        if (!agentContext.unattended) return null
        val target = File(path).canonicalFile.toPath()
        val roots = buildList {
            add(ToolResultGuard.spillDirectory().toFile().canonicalFile.toPath())
            agentContext.projectPath?.toFile()?.canonicalFile?.toPath()?.let { add(it) }
        }
        if (roots.any { target.startsWith(it) }) return null
        return "Error: an unattended run may only read tool-output spill files or files inside the " +
            "project; '$path' is outside those locations. Narrow the upstream query instead."
    }

    companion object {
        private const val TIMEOUT_SECONDS = 10
        /**
         * Step budget for loop/closure iterations. Tight enough to stop runaway loops
         * within milliseconds, generous enough for real data-processing scripts
         * (a 4MB CSV is a few hundred thousand lines).
         */
        private const val MAX_STEPS = 1_000_000L
        /** Max file size: 4MB — covers spilled MCP output while preventing memory exhaustion. */
        private const val MAX_FILE_BYTES = 4L * 1024L * 1024L
    }
}
