package com.easy.easyai.repository.todo

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.TodoStatus
import org.slf4j.LoggerFactory

/**
 * Completion check that verifies all todo items are completed.
 * If there are pending or in-progress items, requests the agent loop to continue.
 */
class TodoCompletionCheck(
    private val todoStore: AsyncTodoStore
) : AgentCompletionCheck {

    private val logger = LoggerFactory.getLogger(javaClass)

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

        if (todos.isEmpty()) {
            return CompletionCheckResult.Done
        }

        val incomplete = todos.filter {
            it.status == TodoStatus.PENDING || it.status == TodoStatus.IN_PROGRESS
        }

        return if (incomplete.isNotEmpty()) {
            logger.info("TodoCompletionCheck: {} incomplete todo(s) found for session {}", incomplete.size, sessionId)
            CompletionCheckResult.Continue(
                prompt = "You still have ${incomplete.size} incomplete todo item(s): " +
                    incomplete.joinToString { "'${it.content}'" } +
                    ". If you have already completed any of these, first use the TodoWrite tool to mark them as COMPLETED; otherwise, please continue working on them."
            )
        } else {
            CompletionCheckResult.Done
        }
    }
}
