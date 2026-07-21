package com.easy.easyai.tools.file

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.ToolPermissionEvaluator
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import java.nio.file.Path

/**
 * Base builder for file-reading tools (read, grep, glob, ls).
 * Provides shared permission evaluator, default rules, and build logic.
 */
abstract class AbstractFileReadToolBuilder : ToolBuilder {
    override val permissionEvaluator = ToolPermissionEvaluator { ctx ->
        ctx.sharedEvaluator.evaluateFilePermission(ctx.rules, ctx.projectPath, ctx.arguments, read = true)
    }
    override val defaultPermissionRules = listOf(
        PermissionRule("file.read.project", "*", PermissionAction.ALLOW)
    )
    override val rulePermissionType = "file.read.other"

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        val workDir = context.projectPath ?: Path.of(".")
        return createTool(workDir)
    }

    protected abstract fun createTool(workDir: Path): ToolDefinition
}

/**
 * Base builder for file-writing tools (write, edit).
 * Provides shared permission evaluator, default rules, and build logic.
 */
abstract class AbstractFileWriteToolBuilder : ToolBuilder {
    override val permissionEvaluator = ToolPermissionEvaluator { ctx ->
        ctx.sharedEvaluator.evaluateFilePermission(ctx.rules, ctx.projectPath, ctx.arguments, read = false)
    }
    override val defaultPermissionRules = listOf(
        PermissionRule("file.write.project", "*", PermissionAction.ALLOW)
    )
    override val rulePermissionType = "file.write.other"

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        val workDir = context.projectPath ?: Path.of(".")
        return createTool(workDir)
    }

    protected abstract fun createTool(workDir: Path): ToolDefinition
}
