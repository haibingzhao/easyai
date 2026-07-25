package com.easy.easyai.swarm.tool

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.AssistantMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Result captured when a team member calls the escalation signal tool
 * ([com.easy.easyai.core.team.MemberSignalTool] with name="escalate").
 *
 * @param reason Why the member cannot proceed.
 * @param progress Optional progress description provided by the member.
 */
data class EscalationResult(val reason: String, val progress: String = "")

/**
 * Completion check that ensures a team member calls the escalate tool
 * ([com.easy.easyai.core.team.MemberSignalTool]) when blocked.
 *
 * Soft-guarantee logic:
 * - If the member already called the escalate tool → [CompletionCheckResult.Done]
 * - If the member's output contains escalation signal words (BLOCKED, UNABLE, ESCALATE)
 *   but did NOT call the tool → [CompletionCheckResult.Continue] with a nudge prompt (max 1 retry)
 * - Otherwise → [CompletionCheckResult.Done] (normal completion)
 *
 * Referenced pattern: [com.easy.easyai.core.validation.OutputSchemaCompletionCheck].
 */
class EscalationCompletionCheck(
    private val escalationRef: AtomicReference<EscalationResult?>,
    private val maxRetries: Int = 1,
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val retryCounters = ConcurrentHashMap<String, Int>()

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        val sessionKey = input.agentContext.sessionId ?: "swarm-member"

        // If escalation was already recorded via tool call, we're done
        if (escalationRef.get() != null) {
            retryCounters.remove(sessionKey)
            return CompletionCheckResult.Done
        }

        // Find the last AssistantMessage
        val lastAssistant = input.transcript.lastOrNull { it is AssistantMessage } as? AssistantMessage
            ?: return CompletionCheckResult.Done

        val text = lastAssistant.text()
        if (text.isBlank()) return CompletionCheckResult.Done

        // Check if output contains escalation signal words
        val hasEscalationSignal = ESCALATION_PATTERNS.any { pattern ->
            pattern.containsMatchIn(text)
        }

        if (!hasEscalationSignal) {
            retryCounters.remove(sessionKey)
            return CompletionCheckResult.Done
        }

        // Output has escalation signal but tool was NOT called — nudge the member
        val retries = retryCounters[sessionKey] ?: 0
        if (retries >= maxRetries) {
            logger.warn("Escalation signal detected but tool not called after {} retries, proceeding", maxRetries)
            retryCounters.remove(sessionKey)
            return CompletionCheckResult.Done
        }

        retryCounters[sessionKey] = retries + 1
        logger.info("Escalation signal detected in member output but escalate tool not called, nudging (attempt {}/{})",
            retries + 1, maxRetries)

        return CompletionCheckResult.Continue(prompt = NUDGE_PROMPT)
    }

    companion object {
        private val ESCALATION_SIGNAL_WORDS = listOf("BLOCKED", "NEED_HELP", "ESCALATE", "UNABLE")

        private val ESCALATION_PATTERNS = ESCALATION_SIGNAL_WORDS.map { word ->
            Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
        }

        private const val NUDGE_PROMPT = """
Your response indicates you are blocked or unable to proceed, but you did not use the 'escalate' tool.
Please call the 'escalate' tool now with a clear description of the issue you are facing.
"""
    }
}
