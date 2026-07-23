package com.easy.easyai.tools.shell

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.*
import com.easy.easyai.tools.executeProcessWithTimeout
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

data class BashCommandParams(
    val command: String,
    @param:JsonPropertyDescription(
        "Idle timeout in seconds (resets on each output line). Range: 10-600, default: 300. " +
        "Process is killed only if no output is received for this duration."
    )
    val timeout: Long? = null
)

/** Minimum idle timeout the LLM may request (seconds). */
private const val MIN_TIMEOUT_SEC = 10L
/** Maximum idle timeout the LLM may request (seconds). */
private const val MAX_TIMEOUT_SEC = 600L
/** Default idle timeout when the LLM omits the parameter (seconds). */
private const val DEFAULT_TIMEOUT_SEC = 200L

/**
 * Execute shell commands via OS subprocess.
 *
 * @param workDir Working directory for the spawned process.
 * @param shellEnv Optional user shell environment variables (e.g. from [com.easy.easyai.tools.shell.ShellEnvProbe])
 *                 merged into the process environment on top of the JVM-inherited env.
 */
class BashTool(
    metadata: ToolMetadata,
    private val workDir: Path? = null,
    private val shellEnv: Map<String, String>? = null
) : BaseToolDefinition(metadata) {
    override val executionMode: ToolExecutionMode get() = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = BashCommandParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val command = args["command"] as? String ?: return@withContext ToolResult(
            content = listOf(ToolResultContent(toolCallId = toolCallId, toolName = name, output = "Error: missing 'command' parameter", isError = true)),
            isError = true
        )
        val timeoutSec = ((args["timeout"] as? Number)?.toLong() ?: DEFAULT_TIMEOUT_SEC)
            .coerceIn(MIN_TIMEOUT_SEC, MAX_TIMEOUT_SEC)
        val shell = resolveBashShell()
        executeProcessWithTimeout(
            command = listOf(shell, "-c", command),
            toolCallId = toolCallId,
            toolName = name,
            timeout = timeoutSec.seconds,
            includeExitCode = true,
            workDir = workDir,
            env = shellEnv,
            onUpdate = onUpdate
        )
    }

}

/**
 * Resolves the best available POSIX-compatible shell on the current platform.
 *
 * Priority: `/bin/bash` > `/usr/bin/bash` > `/bin/zsh` > `/usr/bin/zsh` > `$SHELL` > `/bin/sh`
 * Bash is preferred because the LLM generates commands using bash syntax.
 * Falls back to the first executable candidate found.
 */
private fun resolveBashShell(): String {
    val bashCandidates = listOf("/bin/bash", "/usr/bin/bash", "/bin/zsh", "/usr/bin/zsh", "/bin/sh")
    val preferred = bashCandidates.firstOrNull { File(it).canExecute() }
    if (preferred != null) return preferred
    // Fallback to $SHELL only if no standard POSIX shell is available
    val envShell = System.getenv("SHELL")
    if (envShell != null && File(envShell).canExecute()) return envShell
    return "/bin/sh"
}
