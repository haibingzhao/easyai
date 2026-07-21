package com.easy.easyai.swarm.tool

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.CoroutineScope

/**
 * LLM-callable tool to update global swarm variables at runtime (batch support).
 *
 * Only variables that are:
 * 1. Declared as [com.easy.easyai.swarm.model.SwarmVariable.updatable] in the preset, AND
 * 2. Listed in the task's [com.easy.easyai.swarm.model.SwarmTask.updatableVariables]
 *
 * can be updated. All other attempts are reported as per-variable failures in the result.
 *
 * Accepts a `variables: Map<String, String>` parameter to update multiple variables in a single call.
 * Each variable is individually validated by [UpdatableVariableGuard]; partial success is supported —
 * valid variables are applied while invalid ones are reported without aborting the batch.
 *
 * Writes directly to [com.easy.easyai.swarm.model.SwarmRun.userVars] (a ConcurrentHashMap), so subsequent
 * [com.easy.easyai.swarm.runtime.SwarmWorkerExecutor.renderPrompt] calls automatically see the latest values.
 */
class UpdateVariableTool(
    metadata: ToolMetadata,
    /** Direct reference to SwarmRun.userVars (ConcurrentHashMap — thread-safe). */
    private val userVars: MutableMap<String, String>,
    private val guard: UpdatableVariableGuard,
    private val taskId: String
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        /** Map of variable names to their new values. Each key must be in the updatable variables list. */
        val variables: Map<String, String>
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val params = try {
            SharedObjectMapper.instance.convertValue(args, Parameters::class.java)
        } catch (_: Exception) {
            return errorResult("Invalid parameters. Required: variables (object mapping variable name to new value)")
        }

        if (params.variables.isEmpty()) {
            return errorResult("No variables provided. Pass at least one entry in the 'variables' map.")
        }

        val results = StringBuilder()
        var successCount = 0
        var failCount = 0

        for ((name, value) in params.variables) {
            val error = guard.check(taskId, name)
            if (error != null) {
                results.appendLine("[FAIL] $name: $error")
                failCount++
            } else {
                userVars[name] = value
                results.appendLine("[OK] $name = $value")
                successCount++
            }
        }

        val summary = "Updated $successCount variable(s)" +
            if (failCount > 0) ", $failCount failed." else "."

        return ToolResult(content = listOf(TextContent("$summary\n${results.toString().trimEnd()}")))
    }

    companion object {
        /**
         * Build a tool description that includes the list of updatable variables.
         */
        fun buildDescription(allowedVariables: List<String>): String = """
Update one or more global workflow variables at runtime.

## When to Use
- Share computed results between tasks
- Update workflow state based on analysis results
- Record findings for downstream tasks to consume

## Parameters
- variables: An object mapping variable names to their new values. All keys must be in the updatable variables list.
  Example: {"variables": {"var1": "value1", "var2": "value2"}}

## Behavior
- Multiple variables can be updated in a single call.
- Each variable is validated individually; partial success is possible.
- The result reports success/failure status for each variable.

## Updatable Variables
${allowedVariables.joinToString("\n") { "- $it" }}
        """.trimIndent()
    }
}
