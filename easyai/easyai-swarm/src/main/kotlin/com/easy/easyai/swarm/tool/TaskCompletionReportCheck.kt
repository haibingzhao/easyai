package com.easy.easyai.swarm.tool

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.swarm.model.TaskReportResult
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Completion check that ensures the agent calls [TaskCompletionReportTool] before finishing.
 *
 * Behavior:
 * - If the agent already called report_task_result → [CompletionCheckResult.Done]
 * - If the agent has NOT called it → [CompletionCheckResult.Continue] with a nudge prompt
 *   (up to [maxRetries] nudges, then proceed to avoid infinite loops)
 *
 * Dynamically injected by [com.easy.easyai.swarm.runtime.SwarmWorkerExecutor] when
 * [com.easy.easyai.swarm.model.SwarmTask.reportEnabled] is true.
 *
 * Pattern reference: [EscalationCompletionCheck].
 */
class TaskCompletionReportCheck(
    private val reportRef: AtomicReference<TaskReportResult>,
    private val maxRetries: Int = 1,
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun maxNudges(): Int = maxRetries

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        // Tool was already called — done
        if (reportRef.get() != null) {
            return CompletionCheckResult.Done
        }

        // Check retry budget — the attempt count is kept by the loop's per-run ledger,
        // so this check stays stateless (see AgentCompletionCheck for why that matters)
        if (input.nudgeAttempt >= maxRetries) {
            logger.warn("report_task_result not called after {} retries, proceeding without report", maxRetries)
            return CompletionCheckResult.Done
        }

        logger.info("report_task_result not yet called, nudging agent (attempt {}/{})", input.nudgeAttempt + 1, maxRetries)

        return CompletionCheckResult.Continue(prompt = NUDGE_PROMPT)
    }

    companion object {
        private const val NUDGE_PROMPT = """
You have not reported your task result yet. You MUST call the 'report_task_result' tool before finishing.
Use status="SUCCESS" if you completed the task objectives, or status="FAILED" with a reason if you could not.
"""
    }
}
