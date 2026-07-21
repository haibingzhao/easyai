package com.easy.easyai.common.util

import kotlinx.coroutines.future.await
import kotlinx.coroutines.withTimeout
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Result of a process execution.
 */
data class ProcessResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int
)

/**
 * Destroy a process and all its descendant processes (children first, then root).
 * Prevents orphan/zombie child processes when the parent is killed.
 *
 * Uses coroutine-friendly [await] for waiting, with an overall 3-second timeout.
 */
suspend fun destroyProcessTree(process: Process) {
    val handle = try {
        process.toHandle()
    } catch (_: Exception) {
        process.destroyForcibly()
        return
    }
    // Kill descendants first (leaf -> root)
    handle.descendants().forEach { descendant ->
        try { descendant.destroy() } catch (_: Exception) { /* ignore */ }
    }
    process.destroyForcibly()
    // Wait briefly for graceful shutdown using coroutine-friendly await
    try {
        withTimeout(3.seconds) {
            for (descendant in handle.descendants()) {
                try { descendant.onExit().await() } catch (_: Exception) { /* ignore */ }
            }
            process.onExit().await()
        }
    } catch (_: Exception) {
        // Force-kill any remaining descendants
        handle.descendants().forEach { descendant ->
            try { descendant.destroyForcibly() } catch (_: Exception) { /* ignore */ }
        }
        process.destroyForcibly()
    }
}

/**
 * Low-level process executor with timeout support.
 *
 * Provides a simple non-blocking API for running external processes,
 * capturing stdout/stderr separately, and enforcing a timeout.
 *
 * Returns `null` if the process does not complete within the configured timeout.
 * Callers decide how to handle timeout vs non-zero exit — no exceptions thrown.
 *
 * Usage:
 * ```
 * val result = ProcessExecutor.execute(
 *     command = listOf("git", "status", "--porcelain"),
 *     workDir = Path.of("/my/project"),
 *     timeoutSeconds = 30
 * ) ?: return
 * if (result.exitCode != 0) {
 *     throw RuntimeException("Command failed: ${result.stderr}")
 * }
 * println(result.stdout)
 * ```
 */
object ProcessExecutor {

    /**
     * Execute a command in the given directory with a timeout.
     *
     * Uses [kotlinx.coroutines.future.await] to wait for process completion
     * without blocking the calling thread.
     *
     * @param command The command and arguments to execute
     * @param workDir Working directory for the process (null = inherit JVM's working directory)
     * @param timeoutSeconds Maximum seconds to wait for process completion
     * @param redirectErrorStream If true, merge stderr into stdout (default: false)
     * @return [ProcessResult] if the process completed, `null` if it timed out
     */
    suspend fun execute(
        command: List<String>,
        workDir: Path? = null,
        timeoutSeconds: Long,
        redirectErrorStream: Boolean = false,
        env: Map<String, String> = emptyMap()
    ): ProcessResult? {
        val process = ProcessBuilder(command)
            .apply {
                if (workDir != null) directory(workDir.toFile())
                if (env.isNotEmpty()) environment().putAll(env)
            }
            .redirectErrorStream(redirectErrorStream)
            .start()

        return try {
            withTimeout(timeoutSeconds.seconds) {
                // Read output in a coroutine-friendly way
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = if (redirectErrorStream) "" else process.errorStream.bufferedReader().readText()
                // Non-blocking wait for process exit
                process.onExit().await()
                ProcessResult(
                    stdout = stdout,
                    stderr = stderr,
                    exitCode = process.exitValue()
                )
            }
        } catch (_: Exception) {
            // TimeoutCancellationException or other interruption
            destroyProcessTree(process)
            // Wait for the process tree to fully terminate to avoid zombie processes
            try {
                withTimeout(Duration.parse("1s")) {
                    process.onExit().await()
                }
            } catch (_: Exception) {
                // Process already terminated or waited timed out, ignore
            }
            null
        }
    }
}
