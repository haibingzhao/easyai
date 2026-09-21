package com.easy.easyai.repository.todo

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.model.TodoStatus
import com.easy.easyai.tools.todo.TodoWriteTool
import org.slf4j.LoggerFactory

/**
 * Completion check that guards the todo list the agent committed to **for the current request**.
 *
 * It deliberately does not arbitrate long-term plans — that is [com.easy.easyai.core.goal.GoalCompletionCheck]'s
 * job (registered earlier via `@Order`, with a turn budget persisted in the DB). Two rules keep this
 * check from holding the loop hostage over work nobody asked for yet:
 *
 * - `IN_PROGRESS` always blocks: the agent claimed it was working on something and then stopped.
 * - `PENDING` blocks only when the agent wrote the list during this run, i.e. as an explicit
 *   commitment for this request. Leftover `PENDING` items from an earlier request are not promises
 *   the current answer is late on.
 *
 * Stagnation is detected through [CompletionCheckResult.Continue.signature]: if the blocking set is
 * byte-for-byte identical to the one that was already nudged about, another nudge cannot help, so the
 * check gives up with [CompletionCheckResult.Stalled] and tells the user what is outstanding.
 */
class TodoCompletionCheck(
    private val todoStore: AsyncTodoStore
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)

    override fun maxNudges(): Int = 2

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        val sessionId = input.agentContext.sessionId
        if (sessionId.isNullOrBlank()) {
            return CompletionCheckResult.Done
        }

        // Scope todos by invocation: main agent uses session scope, sub-agents use agentRunId.
        val agentRunId = input.agentContext.agentRunId
        val todos = try {
            todoStore.getTodos(sessionId, agentRunId)
        } catch (e: Exception) {
            logger.warn("Failed to get todos for session {} scope {}: {}", sessionId, agentRunId ?: "main", e.message)
            return CompletionCheckResult.Done
        }

        val blocking = blockingItems(todos, input)
        if (blocking.isEmpty()) {
            return CompletionCheckResult.Done
        }

        val signature = signatureOf(blocking)
        if (signature == input.previousSignature) {
            logger.warn(
                "TodoCompletionCheck: no progress on {} blocking todo(s) for session {} scope {} since last nudge, giving up",
                blocking.size, sessionId, agentRunId ?: "main"
            )
            return CompletionCheckResult.Stalled(notice = buildStallNotice(blocking))
        }

        logger.info(
            "TodoCompletionCheck: {} blocking todo item(s) for session {} scope {}",
            blocking.size, sessionId, agentRunId ?: "main"
        )
        return CompletionCheckResult.Continue(prompt = buildNudgePrompt(blocking), signature = signature)
    }

    /**
     * Todos that justify resuming the loop. See the class doc for why `PENDING` alone does not.
     */
    private fun blockingItems(todos: List<TodoInfo>, input: CompletionCheckInput): List<TodoInfo> {
        val writtenThisRun = TodoWriteTool.TOOL_NAME in input.toolNamesInvoked
        return todos.filter {
            it.status == TodoStatus.IN_PROGRESS ||
                (it.status == TodoStatus.PENDING && writtenThisRun)
        }
    }

    /**
     * Identity of the current blocking set. Built from status + content, never from [TodoInfo.id]:
     * `todo_write` replaces the whole list and regenerates ids on every call, so an id-based
     * signature would change even when the agent did nothing but rewrite the same items.
     */
    private fun signatureOf(blocking: List<TodoInfo>): String =
        blocking.map { "${it.status}|${it.content}" }.sorted().joinToString("||")

    private fun buildNudgePrompt(blocking: List<TodoInfo>): String =
        "You still have ${blocking.size} incomplete todo item(s): " +
            blocking.joinToString { "'${it.content}'" } +
            ". For each one, mark it as completed only if you genuinely finished it; " +
            "if it is not part of this request, mark it as cancelled or drop it from the list. " +
            "Do not call the todo tool just to acknowledge this message — either do the work or " +
            "settle the list, then answer."

    private fun buildStallNotice(blocking: List<TodoInfo>): String =
        "Stopped auto-continuation: ${blocking.size} todo item(s) are still open (" +
            blocking.joinToString { "'${it.content}'" } +
            ") and nothing changed since the last reminder. " +
            "Send a new message to have them worked on."
}
