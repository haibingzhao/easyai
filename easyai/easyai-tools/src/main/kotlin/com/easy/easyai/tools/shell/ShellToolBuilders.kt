package com.easy.easyai.tools.shell

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.ToolPermissionEvaluator
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component
import java.nio.file.Path

/**
 * Builder for [BashTool].
 */
@Component
class BashToolBuilder : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "bash",
        description = """Execute a shell command in the current working directory.
Do NOT use this tool for:
- File content search: use grep instead
- File name matching (find): use glob instead
- Directory listing (ls): use ls instead
- Reading files (cat/head/tail): use read instead
- Calculations (math/date/time/arithmetic): use calc instead
Parameters:
- timeout (optional): Idle timeout in seconds (10-600, default 300). Timer resets on output; process is killed only when idle for this duration.""",
        permissionCategory = "shell",
        tracksFileChanges = true,
        uiRenderer = "bash",
        patternKeys = listOf("command", "cmd")
    )
    override val permissionEvaluator = ToolPermissionEvaluator { ctx ->
        ctx.sharedEvaluator.evaluateShellPermission(ctx.rules, ctx.projectPath, ctx.arguments)
    }
    override val defaultPermissionRules = listOf(
        PermissionRule("shell.safe", "*", PermissionAction.ALLOW),
        PermissionRule("file.read.project", "*", PermissionAction.ALLOW),
        PermissionRule("file.write.project", "*", PermissionAction.ALLOW)
    )
    override val rulePermissionType = "shell.other"

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        val workDir = context.projectPath ?: Path.of(".")
        // Probe the user's login shell env once (cached) so that commands like
        // mvn/node/go resolve correctly even when Spring Boot was launched from
        // an IDE or systemd with an incomplete PATH.
        val shellEnv = ShellEnvProbe.probe().ifEmpty { null }
        return BashTool(metadata, workDir, shellEnv)
    }
}
