package com.easy.easyai.compaction.strategy

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Completion check that ensures the compaction agent calls update_variable before finishing.
 *
 * Behavior:
 * - If the agent already called update_variable → [CompletionCheckResult.Done]
 * - If the agent has NOT called it → [CompletionCheckResult.Continue] with a nudge prompt
 *   (up to [maxRetries] nudges, then proceed to avoid infinite loops)
 *
 * Pattern reference: TaskCompletionReportCheck in easyai-swarm.
 */
class CompactionVariableCompletionCheck(
    private val toolCalled: AtomicBoolean,
    private val maxRetries: Int = 1
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun maxNudges(): Int = maxRetries

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        // Tool was already called — done
        if (toolCalled.get()) {
            return CompletionCheckResult.Done
        }

        // Check retry budget — the attempt count is kept by the loop's per-run ledger
        if (input.nudgeAttempt >= maxRetries) {
            logger.warn("update_variable not called after {} retries, proceeding without variable extraction", maxRetries)
            return CompletionCheckResult.Done
        }

        logger.info("update_variable not yet called, nudging compaction agent (attempt {}/{})", input.nudgeAttempt + 1, maxRetries)
        return CompletionCheckResult.Continue(prompt = NUDGE_PROMPT)
    }

    companion object {
        private const val NUDGE_PROMPT = """
You have not called the 'update_variable' tool yet. You MUST call it EXACTLY ONCE before finishing.
Output the COMPLETE updated variable set (still-valid existing + new). Your output REPLACES the entire store.
Only numeric/data facts (prices, figures, percentages, IDs, configs). Arrays/objects allowed as JSON values.
Do NOT store analysis conclusions or narrative text — those belong in the summary.
Example: {"variables": {"current_price": "170.69", "pe_ttm": "80.28", "customers": ["SMIC", "CXMT"]}}
"""
    }
}
