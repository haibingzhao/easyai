package com.easy.easyai.skills.team

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.AssistantMessage
import org.slf4j.LoggerFactory
import java.util.concurrent.atomic.AtomicReference

/**
 * Completion check ensuring a team member formally signals blocked state
 * via the ask_leader tool instead of merely mentioning it in text.
 *
 * Soft-guarantee logic (same pattern as Swarm's EscalationCompletionCheck):
 * - Member already called ask_leader → [CompletionCheckResult.Done]
 * - Member's output contains block signal words but tool NOT called → nudge (max 1 retry)
 * - Otherwise → [CompletionCheckResult.Done]
 */
class MemberSignalCompletionCheck(
    private val blockedRef: AtomicReference<Pair<String, String>?>,
    private val toolName: String = "ask_leader",
    private val maxRetries: Int = 1,
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)
    private var retries = 0

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        // Signal already recorded via tool call — done
        if (blockedRef.get() != null) {
            return CompletionCheckResult.Done
        }

        val lastAssistant = input.transcript.lastOrNull { it is AssistantMessage } as? AssistantMessage
            ?: return CompletionCheckResult.Done

        val text = lastAssistant.text()
        if (text.isBlank()) return CompletionCheckResult.Done

        val hasBlockSignal = BLOCK_PATTERNS.any { it.containsMatchIn(text) }
        if (!hasBlockSignal) {
            return CompletionCheckResult.Done
        }

        // Output has block signal but tool was NOT called — nudge the member
        if (retries >= maxRetries) {
            logger.warn("Block signal detected but '{}' not called after {} retries, proceeding", toolName, maxRetries)
            return CompletionCheckResult.Done
        }

        retries++
        logger.info("Block signal detected in member output but '{}' not called, nudging (attempt {}/{})",
            toolName, retries, maxRetries)

        return CompletionCheckResult.Continue(prompt =
            "Your response indicates you are blocked or unable to proceed, but you did not use the '$toolName' tool. " +
            "Please call the '$toolName' tool now with a clear description of the issue you are facing."
        )
    }

    companion object {
        private val BLOCK_SIGNAL_WORDS = listOf("BLOCKED", "NEED_HELP", "UNABLE", "CANNOT PROCEED")

        private val BLOCK_PATTERNS = BLOCK_SIGNAL_WORDS.map { word ->
            Regex("\\b${Regex.escape(word)}\\b", RegexOption.IGNORE_CASE)
        }
    }
}
