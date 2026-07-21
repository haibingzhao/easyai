package com.easy.easyai.repository.todo

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.model.TodoPriority
import com.easy.easyai.core.model.TodoStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Unit tests for [TodoCompletionCheck] scoped todo completion behavior.
 */
class TodoCompletionCheckTest {

    private fun todo(content: String, status: TodoStatus) = TodoInfo(
        id = UUID.randomUUID().toString(),
        content = content,
        status = status,
        priority = TodoPriority.MEDIUM,
        position = 0,
        createdAt = System.currentTimeMillis()
    )

    private fun context(sessionId: String, agentRunId: String? = null, parentAgentId: String? = null) = AgentContext(
        agentId = if (parentAgentId == null) "main" else "sub",
        sessionId = sessionId,
        parentAgentId = parentAgentId,
        agentRunId = agentRunId
    )

    @Test
    fun `sub-agent with incomplete todos returns Continue`() = runTest {
        val sessionId = "session-${UUID.randomUUID()}"
        val agentRunId = "run-${UUID.randomUUID()}"
        val store = InMemoryAsyncTodoStore(
            mapOf(sessionId to mapOf(agentRunId to listOf(todo("sub task", TodoStatus.PENDING))))
        )
        val check = TodoCompletionCheck(store)
        val result = check.check(CompletionCheckInput(
            agentContext = context(sessionId, agentRunId, parentAgentId = "main"),
            transcript = emptyList(),
            turnId = 0
        ))

        assertIs<CompletionCheckResult.Continue>(result)
        assertTrue(result.prompt?.contains("incomplete todo") == true)
    }

    @Test
    fun `sub-agent with all completed todos returns Done`() = runTest {
        val sessionId = "session-${UUID.randomUUID()}"
        val agentRunId = "run-${UUID.randomUUID()}"
        val store = InMemoryAsyncTodoStore(
            mapOf(sessionId to mapOf(agentRunId to listOf(todo("sub task", TodoStatus.COMPLETED))))
        )
        val check = TodoCompletionCheck(store)
        val result = check.check(CompletionCheckInput(
            agentContext = context(sessionId, agentRunId, parentAgentId = "main"),
            transcript = emptyList(),
            turnId = 0
        ))

        assertIs<CompletionCheckResult.Done>(result)
    }

    @Test
    fun `main agent todos do not affect sub-agent scope`() = runTest {
        val sessionId = "session-${UUID.randomUUID()}"
        val agentRunId = "run-${UUID.randomUUID()}"
        val store = InMemoryAsyncTodoStore(
            mapOf(
                sessionId to mapOf(
                    null to listOf(todo("main task", TodoStatus.PENDING)),
                    agentRunId to listOf(todo("sub task", TodoStatus.COMPLETED))
                )
            )
        )
        val check = TodoCompletionCheck(store)
        val result = check.check(CompletionCheckInput(
            agentContext = context(sessionId, agentRunId, parentAgentId = "main"),
            transcript = emptyList(),
            turnId = 0
        ))

        assertIs<CompletionCheckResult.Done>(result)
    }

    /**
     * Minimal in-memory store for testing scoped queries.
     */
    private class InMemoryAsyncTodoStore(
        private val data: Map<String, Map<String?, List<TodoInfo>>>
    ) : AsyncTodoStore {
        override suspend fun saveTodos(sessionId: String, agentRunId: String?, todos: List<TodoInfo>) {
            throw UnsupportedOperationException()
        }

        override suspend fun getTodos(sessionId: String, agentRunId: String?): List<TodoInfo> {
            return data[sessionId]?.get(agentRunId) ?: emptyList()
        }

        override suspend fun deleteTodos(sessionId: String, agentRunId: String?) {
            throw UnsupportedOperationException()
        }

        override suspend fun deleteAllTodos(sessionId: String) {
            throw UnsupportedOperationException()
        }

        override suspend fun getAllTodos(sessionId: String): Map<String?, List<TodoInfo>> {
            return data[sessionId] ?: emptyMap()
        }
    }
}
