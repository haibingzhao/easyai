package com.easy.easyai.swarm.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.easy.easyai.swarm.model.SwarmRun
import com.easy.easyai.swarm.model.SwarmTaskStatus
import com.easy.easyai.swarm.preset.SwarmPresetStore
import com.easy.easyai.swarm.runtime.SwarmRuntime
import com.easy.easyai.swarm.store.SwarmRunStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import com.easy.easyai.common.util.SharedObjectMapper

/**
 * LLM-callable tool to launch a swarm run.
 *
 * The LLM invokes this with a preset name and optional variables.
 * The tool builds a SwarmRun from the preset, executes it via SwarmRuntime,
 * and returns a summary of results.
 */
class SwarmTool(
    metadata: ToolMetadata,
    private val runtime: SwarmRuntime,
    private val presetStore: SwarmPresetStore,
    private val store: SwarmRunStore? = null
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        /** Preset name (e.g., "investment_committee"). */
        val presetName: String,
        /** User variables for template substitution. */
        val variables: Map<String, String> = emptyMap()
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
            return ToolResult(
                content = listOf(TextContent(
                    "Error: Invalid parameters. Required: presetName (String), variables (Map<String, String>)"
                )),
                isError = true
            )
        }

        onUpdate(ToolUpdate.Progress("Loading swarm preset '${params.presetName}'..."))

        val userId = agentContext.userId ?: "system"

        // Build run from preset
        val run = try {
            presetStore.buildRun(params.presetName, userId, params.variables)
        } catch (e: Exception) {
            return ToolResult(
                content = listOf(TextContent("Error building swarm run: ${e.message}")),
                isError = true
            )
        }

        onUpdate(ToolUpdate.Progress(
            "Starting swarm '${run.title}' (runId: ${run.id}): ${run.tasks.size} tasks, ${run.agents.size} agents..."
        ))

        logger.info("SwarmTool starting run '{}': preset={}, tasks={}, agents={}",
            run.id, params.presetName, run.tasks.size, run.agents.size)

        // Persist run before execution
        store?.saveRun(run, userId)

        // Execute the swarm run
        val completedRun = try {
            runtime.execute(
                run = run,
                abortSignal = agentContext.abortSignal
            )
        } catch (e: CancellationException) {
            // Persist partial results before cancellation propagates
            withContext(NonCancellable) {
                store?.persistRunResults(run, userId)
            }
            throw e
        } catch (e: Exception) {
            logger.error("Swarm run '{}' failed: {}", run.id, e.message, e)
            // Persist failure state
            withContext(NonCancellable) {
                store?.persistRunResults(run, userId)
            }
            return ToolResult(
                content = listOf(TextContent("Swarm execution failed: ${e.message}")),
                isError = true
            )
        }

        // Persist results after execution
        withContext(NonCancellable) {
            store?.persistRunResults(completedRun, userId)
        }

        // Build result summary
        val summary = buildResultSummary(completedRun)

        onUpdate(ToolUpdate.Progress("Swarm run completed: ${completedRun.status}"))

        return ToolResult(
            content = listOf(TextContent(summary)),
            details = mapOf(
                "runId" to completedRun.id,
                "status" to completedRun.status.name,
                "presetName" to completedRun.presetName
            )
        )
    }

    /**
     * Build a human-readable summary of the swarm run results.
     */
    private fun buildResultSummary(run: SwarmRun): String {
        val sb = StringBuilder()
        sb.appendLine("# Swarm Run: ${run.title}")
        sb.appendLine("**Status**: ${run.status}")
        sb.appendLine("**Run ID**: ${run.id}")
        sb.appendLine("**Tokens**: ${run.totalInputTokens} input / ${run.totalOutputTokens} output / ${run.totalCacheReadTokens} cache-read / ${run.totalCacheWriteTokens} cache-write")
        sb.appendLine("**Duration**: ${run.totalDurationMs}ms")
        sb.appendLine()

        // Task results
        sb.appendLine("## Task Results")
        for (task in run.tasks) {
            val statusIcon = when (task.status) {
                SwarmTaskStatus.COMPLETED -> "[OK]"
                SwarmTaskStatus.FAILED -> "[FAIL]"
                SwarmTaskStatus.CANCELLED -> "[CANCELLED]"
                else -> "[${task.status}]"
            }
            sb.appendLine()
            sb.appendLine("### $statusIcon ${task.id}")
            sb.appendLine("- **Type**: ${task.type}")
            sb.appendLine("- **Iterations**: ${task.workerIterations}")
            sb.appendLine("- **Tokens**: ${task.inputTokens} input / ${task.outputTokens} output / ${task.cacheReadTokens} cache-read / ${task.cacheWriteTokens} cache-write")
            sb.appendLine("- **Duration**: ${task.durationMs}ms")
            if (task.error != null) {
                sb.appendLine("- **Error**: ${task.error}")
            }
            if (task.summary != null) {
                sb.appendLine()
                sb.appendLine(task.summary!!.take(MAX_RESULT_CHARS))
                if (task.summary!!.length > MAX_RESULT_CHARS) {
                    sb.appendLine("\n[Truncated]")
                }
            }
        }

        return sb.toString()
    }

    companion object {
        const val MAX_RESULT_CHARS = 8_000

        val DESCRIPTION = """
Launch a multi-agent swarm run using a predefined workflow.

## When to Use
- Complex analysis requiring multiple specialized agents working in parallel
- Tasks requiring deliberation (debate, review, consensus building)
- Multi-step research with dependency chains between tasks

## Parameters
- presetName: Name of the preset workflow (e.g., "investment_committee")
- variables: Key-value pairs for template substitution in prompts

## How It Works
1. Loads the preset definition (agents, tasks, dependencies)
2. Executes tasks in topological order (parallel within layers)
3. Passes upstream results to downstream tasks
4. Returns comprehensive results from all agents

## Available Presets
Use the REST API `GET /api/swarm/presets` to see available preset names.
        """.trimIndent()
    }
}
