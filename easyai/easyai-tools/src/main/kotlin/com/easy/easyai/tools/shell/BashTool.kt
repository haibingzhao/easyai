package com.easy.easyai.tools.shell

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.tools.executeProcessWithTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path
import kotlin.time.Duration.Companion.seconds

data class BashCommandParams(
    val command: String,
    val timeout: Long? = null
)

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
        val timeoutSec = (args["timeout"] as? Number)?.toLong() ?: 300L
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
