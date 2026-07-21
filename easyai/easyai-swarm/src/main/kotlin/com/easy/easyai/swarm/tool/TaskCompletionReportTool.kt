package com.easy.easyai.swarm.tool

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.easy.easyai.swarm.model.TaskReportResult
import com.easy.easyai.swarm.model.TaskReportStatus
import kotlinx.coroutines.CoroutineScope
import java.util.concurrent.atomic.AtomicReference

/**
 * LLM-callable tool that lets the agent explicitly report task success or failure.
 *
 * Dynamically injected by [com.easy.easyai.swarm.runtime.SwarmWorkerExecutor] when
 * [com.easy.easyai.swarm.model.SwarmTask.reportEnabled] is true.
 *
 * Works in tandem with [TaskCompletionReportCheck]:
 * - The check ensures the agent calls this tool before finishing (nudge via Continue prompt).
 * - This tool records the result in [reportRef] for [com.easy.easyai.swarm.runtime.SwarmRuntime]
 *   to inspect and act on (FAILED → DAG cascade block).
 *
 * Pattern reference: [UpdateVariableTool].
 */
class TaskCompletionReportTool(
    metadata: ToolMetadata,
    private val reportRef: AtomicReference<TaskReportResult>,
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        /** "SUCCESS" or "FAILED" */
        val status: String,
        /** Brief reason — required when status=FAILED */
        val reason: String = "",
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
            return errorResult("Invalid parameters. Required: status (String: SUCCESS or FAILED), reason (String)")
        }

        val reportStatus = when (params.status.uppercase()) {
            "SUCCESS" -> TaskReportStatus.SUCCESS
            "FAILED" -> TaskReportStatus.FAILED
            else -> return errorResult("Invalid status '${params.status}'. Must be SUCCESS or FAILED.")
        }

        if (reportStatus == TaskReportStatus.FAILED && params.reason.isBlank()) {
            return errorResult("reason is required when status=FAILED")
        }

        reportRef.set(TaskReportResult(reportStatus, params.reason))
        return ToolResult(content = listOf(TextContent("Task result reported: ${params.status}")))
    }

    companion object {
        const val TOOL_NAME = "report_task_result"

        fun buildDescription(): String = """
Report the final result of your task. You MUST call this tool before finishing.

## Parameters
- status: "SUCCESS" if you completed the task objectives, "FAILED" if you could not
- reason: Brief explanation (required when status=FAILED)

## When to Report FAILED
- You could not obtain critical data or information needed for the task
- External errors prevented task completion (network failures, API errors, etc.)
- You determined the task objectives cannot be met

## Important
Always call this tool as the LAST step of your task. The workflow engine depends on this report
to decide whether downstream tasks should proceed.
        """.trimIndent()
    }
}
