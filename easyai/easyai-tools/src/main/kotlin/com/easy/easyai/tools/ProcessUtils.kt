package com.easy.easyai.tools

import com.easy.easyai.common.util.destroyProcessTree
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.time.Duration

/** Default maximum output size in bytes (1 MB). */
internal const val DEFAULT_MAX_OUTPUT_BYTES: Int = 1_048_576

/**
 * Result of executing a process with timeout.
 */
data class ProcessExecutionResult(
    val output: String,
    val exitCode: Int?,
    val timedOut: Boolean,
    val truncated: Boolean = false
)

/**
 * Execute a process with timeout, returning raw output and exit code.
 * Caller is responsible for building ToolResult from the result.
 *
 * Output is bounded to [maxOutputBytes] using a tail-retention strategy:
 * when the limit is exceeded, only the most recent lines are kept
 * (the tail is most valuable for LLM consumption).
 *
 * @param command The command and arguments to execute
 * @param timeout Maximum duration to wait for process completion
 * @param workDir Working directory for the process
 * @param maxOutputBytes Maximum bytes to retain in memory (default: 1 MB)
 * @param env Optional environment variables to merge into the process environment
 * @param onUpdate Callback for streaming partial output lines
 * @return ProcessExecutionResult containing raw output and exit code
 */
internal suspend fun executeProcess(
    command: List<String>,
    timeout: Duration,
    workDir: Path? = null,
    maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    env: Map<String, String>? = null,
    onUpdate: suspend (ToolUpdate) -> Unit = {}
): ProcessExecutionResult = coroutineScope {
    val process = ProcessBuilder(command)
        .apply {
            if (workDir != null) directory(workDir.toFile())
            if (env != null) environment().putAll(env)
        }
        .redirectErrorStream(true)
        .start()

    // Bounded ring buffer: retains only the tail when output exceeds maxOutputBytes
    val chunks = ArrayDeque<Pair<String, Int>>()
    var used = 0
    var truncated = false

    // Reader coroutine: drains stdout line-by-line, streaming partial output via onUpdate.
    // MUST run on Dispatchers.IO: readLine() is blocking Java I/O, and running it on the
    // caller's dispatcher (e.g. a single-threaded event loop) would block the timeout timer
    // from firing. On Dispatchers.IO the blocked read occupies one pool thread while the
    // timeout below is free to fire. It is unblocked when the process is destroyed
    // (destroyForcibly closes the stdout pipe → readLine() returns null).
    val reader = process.inputStream.bufferedReader()
    val readerJob = launch(Dispatchers.IO) {
        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val lineStr = line!!
            val size = lineStr.toByteArray(Charsets.UTF_8).size + 1 // +1 for newline
            chunks.addLast(lineStr to size)
            used += size
            // Evict oldest lines when over the limit
            while (used > maxOutputBytes && chunks.size > 1) {
                val removed = chunks.removeFirst()
                used -= removed.second
                truncated = true
            }
            onUpdate(ToolUpdate.PartialContent("$lineStr\n"))
        }
    }

    // Wait for process exit within the timeout. process.onExit().await() is a proper
    // suspension point, so the timeout can actually fire here — unlike a blocked readLine().
    // On timeout OR external cancellation, destroy the process in finally so the reader
    // coroutine is always unblocked and never leaks a running process.
    var exitedWithinTimeout = false
    try {
        exitedWithinTimeout = withTimeoutOrNull(timeout) {
            process.onExit().await()
            true
        } ?: false
    } finally {
        if (!exitedWithinTimeout) {
            destroyProcessTree(process)
        }
    }

    // Wait for the reader to finish consuming any buffered output before building the result.
    // The process has exited or been destroyed, so readLine() reaches EOF promptly.
    readerJob.join()

    val output = buildString {
        if (truncated) append("...output truncated, kept last $maxOutputBytes bytes...\n\n")
        chunks.forEach { appendLine(it.first) }
    }

    ProcessExecutionResult(
        output = output,
        exitCode = if (exitedWithinTimeout) process.exitValue() else null,
        timedOut = !exitedWithinTimeout,
        truncated = truncated
    )
}

/**
 * Execute a process with timeout, stream output line-by-line via onUpdate,
 * and build a ToolResult based on the execution outcome.
 *
 * @param command The command and arguments to execute
 * @param toolCallId The tool call identifier
 * @param toolName The tool name for result content
 * @param timeout Maximum duration to wait for process completion
 * @param trimOutput Whether to trim trailing whitespace from output (default: false)
 * @param emptyResultMessage Optional message to return when exitCode == 0 but output is empty
 * @param includeExitCode Whether to include exitCode and isError in the result (default: false)
 * @param workDir Working directory for the process (default: null, inherits JVM's working directory)
 * @param maxOutputBytes Maximum bytes to retain in memory (default: [DEFAULT_MAX_OUTPUT_BYTES])
 * @param env Optional environment variables to merge into the process environment
 * @param onUpdate Callback for streaming partial output lines
 * @return ToolResult containing the process output or error information
 */
internal suspend fun executeProcessWithTimeout(
    command: List<String>,
    toolCallId: String,
    toolName: String,
    timeout: Duration,
    trimOutput: Boolean = false,
    emptyResultMessage: String? = null,
    includeExitCode: Boolean = false,
    workDir: Path? = null,
    maxOutputBytes: Int = DEFAULT_MAX_OUTPUT_BYTES,
    env: Map<String, String>? = null,
    onUpdate: suspend (ToolUpdate) -> Unit
): ToolResult {
    val result = executeProcess(command, timeout, workDir, maxOutputBytes, env, onUpdate)

    if (result.timedOut) {
        return ToolResult(content = listOf(ToolResultContent(
            toolCallId = toolCallId,
            toolName = toolName,
            output = result.output,
            exitCode = null,
            isError = true,
            truncated = result.truncated
        )))
    }

    val exitCode = result.exitCode!!
    val processedOutput = if (trimOutput) result.output.trimEnd() else result.output

    // Handle empty result message for successful but empty output
    if (exitCode == 0 && emptyResultMessage != null && processedOutput.isBlank()) {
        return ToolResult(content = listOf(ToolResultContent(
            toolCallId = toolCallId,
            toolName = toolName,
            output = emptyResultMessage,
            truncated = result.truncated
        )))
    }

    // Include exitCode and isError if requested
    if (includeExitCode) {
        return ToolResult(content = listOf(ToolResultContent(
            toolCallId = toolCallId,
            toolName = toolName,
            output = processedOutput,
            exitCode = exitCode,
            isError = (exitCode != 0),
            truncated = result.truncated
        )))
    }

    // Default: return processed output without exitCode
    return ToolResult(content = listOf(ToolResultContent(
        toolCallId = toolCallId,
        toolName = toolName,
        output = processedOutput,
        truncated = result.truncated
    )))
}