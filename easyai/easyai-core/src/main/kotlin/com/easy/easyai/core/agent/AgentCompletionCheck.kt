package com.easy.easyai.core.agent

import com.easy.easyai.core.model.EasyAiMessage

/**
 * Hook for checking whether the agent task is truly complete
 * when the agent loop is about to stop (continueLoop = false).
 *
 * Multiple checks can be registered on [AgentService.completionChecks].
 * If ANY check returns [CompletionCheckResult.Continue], the loop resumes.
 *
 * ## State ownership
 *
 * Implementations should stay **stateless**: the loop keeps a per-run ledger
 * (attempt count + last [CompletionCheckResult.Continue.signature]) and hands it
 * back through [CompletionCheckInput]. A check that stores counters on itself is a
 * bug waiting to happen — globally registered checks are Spring singletons shared by
 * every concurrent session, so instance state leaks across runs and across sessions.
 */
fun interface AgentCompletionCheck {
    suspend fun check(input: CompletionCheckInput): CompletionCheckResult

    /**
     * Hard cap on how many times this check may resume the loop within a single run.
     *
     * This is a safety net, not the primary exit: a check should normally stop itself
     * earlier by returning [CompletionCheckResult.Done] or [CompletionCheckResult.Stalled]
     * once it detects that the agent made no progress since the previous nudge.
     */
    fun maxNudges(): Int = DEFAULT_MAX_NUDGES

    companion object {
        /** Default per-run nudge cap applied to checks that do not override [maxNudges]. */
        const val DEFAULT_MAX_NUDGES: Int = 3

        /** [AgentLoop.endReason] value set when a completion check stops auto-continuation. */
        const val END_REASON_STALLED: String = "completion_check_stalled"
    }
}

/**
 * Input provided to [AgentCompletionCheck] when the agent loop is about to stop.
 *
 * @param nudgeAttempt How many times this check has already been honoured (Continue)
 *   during the current run. Supplied by the loop ledger; 0 on the first check of a run.
 * @param previousSignature The [CompletionCheckResult.Continue.signature] this check
 *   submitted on its previous nudge, or null if this is its first nudge of the run.
 *   Comparing it against the freshly computed signature is how a check detects stagnation.
 * @param toolNamesInvoked Names of the tools executed so far in the current run.
 *   Lets a check tell whether the agent touched a resource during this request. Derived from
 *   the run itself rather than the transcript, so context compaction cannot mislead it.
 */
data class CompletionCheckInput(
    val agentContext: AgentContext,
    val transcript: List<EasyAiMessage>,
    val turnId: Int,
    val nudgeAttempt: Int = 0,
    val previousSignature: String? = null,
    val toolNamesInvoked: Set<String> = emptySet(),
)

/**
 * Result of a completion check.
 */
sealed class CompletionCheckResult {
    /** Task is complete, no need to continue. */
    data object Done : CompletionCheckResult()

    /**
     * Task is not complete, loop should continue.
     *
     * @param prompt Optional message to inject as UserMessage to guide the next LLM iteration.
     * @param signature Stable identifier of what is still blocking the task. The loop records it
     *   and feeds it back as [CompletionCheckInput.previousSignature] on the next check, letting
     *   the check notice that nothing moved. Build it from semantic content only — never from
     *   generated ids, which may be regenerated on every write (see the todo store).
     */
    data class Continue(
        val prompt: String? = null,
        val signature: String? = null,
    ) : CompletionCheckResult()

    /**
     * The check gives up: further nudging would be pointless, so do not resume the loop,
     * but surface [notice] to the user instead of ending silently.
     */
    data class Stalled(val notice: String? = null) : CompletionCheckResult()
}
