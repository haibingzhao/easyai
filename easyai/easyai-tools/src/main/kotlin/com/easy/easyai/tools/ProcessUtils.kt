package com.easy.easyai.tools

import com.easy.easyai.common.util.destroyProcessTree
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.future.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
 * Execute a process with idle timeout (stall detection), returning raw output and exit code.
 * Caller is responsible for building ToolResult from the result.
 *
 * The timeout is an **idle timeout**: the timer resets on every output line received.
 * The process is killed only when no output is received for the full [timeout] duration.
 * A continuously producing process will never be killed by this timeout.
 *
 * Output is bounded to [maxOutputBytes] using a tail-retention strategy:
 * when the limit is exceeded, only the most recent lines are kept
 * (the tail is most valuable for LLM consumption).
 *
 * @param command The command and arguments to execute
 * @param timeout Idle timeout — the process is killed only if no output is received for
 *                this duration. The timer resets on every output line.
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

    // Activity signal channel: reader sends Unit for each output line.
    // The consumer loop below uses withTimeoutOrNull to detect stalls —
    // mirrors the Channel-based stall detection in AgentLoopRunner.
    val activityChannel = Channel<Unit>(Channel.UNLIMITED)

    // Reader coroutine: drains stdout line-by-line, streaming partial output via onUpdate.
    // MUST run on Dispatchers.IO: readLine() is blocking Java I/O, and running it on the
    // caller's dispatcher (e.g. a single-threaded event loop) would block the timeout timer
    // from firing. On Dispatchers.IO the blocked read occupies one pool thread while the
    // timeout below is free to fire. It is unblocked when the process is destroyed
    // (destroyForcibly closes the stdout pipe → readLine() returns null).
    val reader = process.inputStream.bufferedReader()
    val readerJob = launch(Dispatchers.IO) {
        try {
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
                activityChannel.send(Unit) // signal activity — resets the idle timer
            }
        } finally {
            activityChannel.close() // EOF or process destroyed
        }
    }

    // Idle timeout (stall detection): timer resets on every output line received.
    // Mirrors the Channel-based stall detection pattern in AgentLoopRunner.
    var exitedNormally = false
    try {
        while (true) {
            val signal = withTimeoutOrNull(timeout) {
                activityChannel.receiveCatching()
            }
            if (signal == null) break  // Idle timeout: no output for `timeout` → stall
            if (signal.isClosed) {     // Channel closed → reader EOF → process exited
                exitedNormally = true
                break
            }
            // Received line signal → loop continues, withTimeoutOrNull timer resets
        }
    } finally {
        if (!exitedNormally) {
            destroyProcessTree(process)
        }
    }

    // Wait for the reader to finish consuming any buffered output before building the result.
    // The process has exited or been destroyed, so readLine() reaches EOF promptly.
    readerJob.join()

    // For normal exit: wait for the process to fully terminate after stdout closes.
    // 5s grace period prevents hanging if the process closes stdout but keeps running.
    val exitCode: Int? = if (exitedNormally) {
        val exited = withTimeoutOrNull(5.seconds) { process.onExit().await(); true } ?: false
        if (exited) process.exitValue() else { destroyProcessTree(process); null }
    } else null

    val output = buildString {
        if (truncated) append("...output truncated, kept last $maxOutputBytes bytes...\n\n")
        chunks.forEach { appendLine(it.first) }
    }

    ProcessExecutionResult(
        output = output,
        exitCode = exitCode,
        timedOut = !exitedNormally,
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
 * @param timeout Idle timeout — process is killed only if no output is received for this duration
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
        val timeoutMessage = "[ERROR] Process stalled: no output received for ${timeout.inWholeSeconds}s and was terminated.\n"
        val outputWithReason = timeoutMessage + result.output
        return ToolResult(
            content = listOf(ToolResultContent(
                toolCallId = toolCallId,
                toolName = toolName,
                output = outputWithReason,
                exitCode = null,
                isError = true,
                truncated = result.truncated
            )),
            isError = true
        )
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
        val isErrorCode = exitCode != 0
        return ToolResult(
            content = listOf(ToolResultContent(
                toolCallId = toolCallId,
                toolName = toolName,
                output = processedOutput,
                exitCode = exitCode,
                isError = isErrorCode,
                truncated = result.truncated
            )),
            isError = isErrorCode
        )
    }

    // Default: return processed output without exitCode
    return ToolResult(content = listOf(ToolResultContent(
        toolCallId = toolCallId,
        toolName = toolName,
        output = processedOutput,
        truncated = result.truncated
    )))
}