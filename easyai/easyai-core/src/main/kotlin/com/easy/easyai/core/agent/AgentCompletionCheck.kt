package com.easy.easyai.core.agent

import com.easy.easyai.core.model.EasyAiMessage

/**
 * Hook for checking whether the agent task is truly complete
 * when the agent loop is about to stop (continueLoop = false).
 *
 * Multiple checks can be registered on [AgentService.completionChecks].
 * If ANY check returns [CompletionCheckResult.Continue], the loop resumes.
 */
fun interface AgentCompletionCheck {
    suspend fun check(input: CompletionCheckInput): CompletionCheckResult
}

/**
 * Input provided to [AgentCompletionCheck] when the agent loop is about to stop.
 */
data class CompletionCheckInput(
    val agentContext: AgentContext,
    val transcript: List<EasyAiMessage>,
    val turnId: Int
)

/**
 * Result of a completion check.
 */
sealed class CompletionCheckResult {
    /** Task is complete, no need to continue. */
    data object Done : CompletionCheckResult()

    /**
     * Task is not complete, loop should continue.
     * @param prompt Optional message to inject as UserMessage to guide the next LLM iteration.
     */
    data class Continue(val prompt: String? = null) : CompletionCheckResult()
}
