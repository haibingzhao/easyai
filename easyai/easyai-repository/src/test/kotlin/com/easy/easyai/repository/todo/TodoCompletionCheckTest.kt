package com.easy.easyai.repository.todo

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompletionCheckInput
import com.easy.easyai.core.agent.CompletionCheckResult
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.model.TodoPriority
import com.easy.easyai.core.model.TodoStatus
import com.easy.easyai.tools.todo.TodoWriteTool
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for [TodoCompletionCheck]: scoped lookups, which statuses may block the loop,
 * and stagnation detection through the signature supplied by the agent loop ledger.
 */
class TodoCompletionCheckTest {

    private fun todo(content: String, status: TodoStatus, id: String = UUID.randomUUID().toString()) = TodoInfo(
        id = id,
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

    private fun storeOf(sessionId: String, agentRunId: String?, todos: List<TodoInfo>) =
        InMemoryAsyncTodoStore(mutableMapOf(sessionId to mutableMapOf<String?, List<TodoInfo>>(agentRunId to todos)))

    private fun setTodos(store: InMemoryAsyncTodoStore, sessionId: String, agentRunId: String?, todos: List<TodoInfo>) {
        store.data.getOrPut(sessionId) { mutableMapOf() }[agentRunId] = todos
    }

    private fun inputOf(
        context: AgentContext,
        nudgeAttempt: Int = 0,
        previousSignature: String? = null,
        toolNamesInvoked: Set<String> = emptySet(),
        transcript: List<EasyAiMessage> = emptyList(),
    ) = CompletionCheckInput(
        agentContext = context,
        transcript = transcript,
        turnId = 0,
        nudgeAttempt = nudgeAttempt,
        previousSignature = previousSignature,
        toolNamesInvoked = toolNamesInvoked,
    )

    @Nested
    inner class `Scoping` {

        @Test
        fun `sub-agent todos are read from its own agentRunId scope`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val agentRunId = "run-${UUID.randomUUID()}"
            val check = TodoCompletionCheck(storeOf(sessionId, agentRunId, listOf(todo("sub task", TodoStatus.IN_PROGRESS))))

            val result = check.check(inputOf(context(sessionId, agentRunId, parentAgentId = "main")))

            assertIs<CompletionCheckResult.Continue>(result)
            assertTrue(result.prompt?.contains("sub task") == true)
        }

        @Test
        fun `main agent todos do not affect sub-agent scope`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val agentRunId = "run-${UUID.randomUUID()}"
            val store = InMemoryAsyncTodoStore(
                mutableMapOf(
                    sessionId to mutableMapOf<String?, List<TodoInfo>>(
                        null to listOf(todo("main task", TodoStatus.IN_PROGRESS)),
                        agentRunId to listOf(todo("sub task", TodoStatus.COMPLETED))
                    )
                )
            )

            val result = TodoCompletionCheck(store).check(inputOf(context(sessionId, agentRunId, parentAgentId = "main")))

            assertIs<CompletionCheckResult.Done>(result)
        }

        @Test
        fun `blank session id cannot be scoped so the check passes`() = runTest {
            val check = TodoCompletionCheck(InMemoryAsyncTodoStore(mutableMapOf()))

            assertIs<CompletionCheckResult.Done>(check.check(inputOf(context(sessionId = "  "))))
        }

        @Test
        fun `store failure does not block the loop`() = runTest {
            val check = TodoCompletionCheck(FailingTodoStore())

            assertIs<CompletionCheckResult.Done>(check.check(inputOf(context("session-x"))))
        }
    }

    @Nested
    inner class `Which statuses block` {

        @Test
        fun `in_progress always blocks even when the list was not written this run`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val check = TodoCompletionCheck(storeOf(sessionId, null, listOf(todo("wip", TodoStatus.IN_PROGRESS))))

            val result = check.check(inputOf(context(sessionId)))

            assertIs<CompletionCheckResult.Continue>(result)
        }

        @Test
        fun `pending only blocks when the list was written during this run`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val check = TodoCompletionCheck(storeOf(sessionId, null, listOf(todo("later work", TodoStatus.PENDING))))

            // Leftover from an earlier request: not a promise this answer is late on.
            assertIs<CompletionCheckResult.Done>(check.check(inputOf(context(sessionId))))

            val committed = check.check(
                inputOf(context(sessionId), toolNamesInvoked = setOf(TodoWriteTool.TOOL_NAME))
            )
            assertIs<CompletionCheckResult.Continue>(committed)
        }

        @Test
        fun `completed and cancelled never block`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val check = TodoCompletionCheck(
                storeOf(
                    sessionId, null,
                    listOf(todo("done", TodoStatus.COMPLETED), todo("dropped", TodoStatus.CANCELLED))
                )
            )

            val result = check.check(
                inputOf(context(sessionId), toolNamesInvoked = setOf(TodoWriteTool.TOOL_NAME))
            )

            assertIs<CompletionCheckResult.Done>(result)
        }

        @Test
        fun `prompt offers cancelling as an exit and lists only blocking items`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val check = TodoCompletionCheck(
                storeOf(
                    sessionId, null,
                    listOf(todo("wip task", TodoStatus.IN_PROGRESS), todo("unrelated pending", TodoStatus.PENDING))
                )
            )

            val result = check.check(inputOf(context(sessionId)))

            assertIs<CompletionCheckResult.Continue>(result)
            val prompt = result.prompt.orEmpty()
            assertTrue(prompt.contains("wip task"))
            assertTrue(
                !prompt.contains("unrelated pending"),
                "pending items must not be listed when the list was not written this run"
            )
            assertTrue(prompt.contains("cancelled"), "prompt must offer the cancelled exit")
            assertTrue(
                prompt.contains("Do not call the todo tool just to acknowledge"),
                "prompt must not invite an empty todo_write call, which would resume the loop on its own"
            )
        }
    }

    @Nested
    inner class `Stagnation detection` {

        @Test
        fun `unchanged blocking set after a nudge stalls instead of nudging again`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val check = TodoCompletionCheck(storeOf(sessionId, null, listOf(todo("wip", TodoStatus.IN_PROGRESS))))

            val first = check.check(inputOf(context(sessionId)))
            assertIs<CompletionCheckResult.Continue>(first)
            val signature = first.signature
            assertTrue(!signature.isNullOrBlank(), "a nudge must carry a signature for stagnation detection")

            val second = check.check(inputOf(context(sessionId), nudgeAttempt = 1, previousSignature = signature))
            assertIs<CompletionCheckResult.Stalled>(second)
            assertTrue(second.notice?.contains("wip") == true, "stall notice must name the outstanding items")
        }

        @Test
        fun `signature survives todo id regeneration`() = runTest {
            // todo_write replaces the whole list and mints fresh UUIDs on every call, so an
            // id-based signature would report "progress" for a verbatim rewrite of the same list.
            val sessionId = "session-${UUID.randomUUID()}"
            val store = storeOf(sessionId, null, listOf(todo("same content", TodoStatus.IN_PROGRESS, id = "id-before")))
            val check = TodoCompletionCheck(store)

            val first = check.check(inputOf(context(sessionId)))
            assertIs<CompletionCheckResult.Continue>(first)

            setTodos(store, sessionId, null, listOf(todo("same content", TodoStatus.IN_PROGRESS, id = "id-after")))
            val second = check.check(inputOf(context(sessionId), nudgeAttempt = 1, previousSignature = first.signature))

            assertIs<CompletionCheckResult.Stalled>(second)
        }

        @Test
        fun `progress on the list earns another nudge`() = runTest {
            val sessionId = "session-${UUID.randomUUID()}"
            val store = storeOf(
                sessionId, null,
                listOf(todo("step 1", TodoStatus.IN_PROGRESS), todo("step 2", TodoStatus.PENDING))
            )
            val check = TodoCompletionCheck(store)

            val first = check.check(inputOf(context(sessionId)))
            assertIs<CompletionCheckResult.Continue>(first)

            // The agent finished step 1 and moved to step 2: the blocking set changed, so
            // another nudge is worthwhile rather than giving up.
            setTodos(
                store, sessionId, null,
                listOf(todo("step 1", TodoStatus.COMPLETED), todo("step 2", TodoStatus.IN_PROGRESS))
            )
            val second = check.check(inputOf(context(sessionId), nudgeAttempt = 1, previousSignature = first.signature))

            assertIs<CompletionCheckResult.Continue>(second)
        }

        @Test
        fun `nudge cap is two`() {
            assertEquals(2, TodoCompletionCheck(InMemoryAsyncTodoStore(mutableMapOf())).maxNudges())
        }
    }

    /**
     * Minimal in-memory store for testing scoped queries.
     */
    private class InMemoryAsyncTodoStore(
        val data: MutableMap<String, MutableMap<String?, List<TodoInfo>>>
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

    private class FailingTodoStore : AsyncTodoStore {
        override suspend fun saveTodos(sessionId: String, agentRunId: String?, todos: List<TodoInfo>) {
            throw UnsupportedOperationException()
        }

        override suspend fun getTodos(sessionId: String, agentRunId: String?): List<TodoInfo> =
            throw IllegalStateException("db unavailable")

        override suspend fun deleteTodos(sessionId: String, agentRunId: String?) {
            throw UnsupportedOperationException()
        }

        override suspend fun deleteAllTodos(sessionId: String) {
            throw UnsupportedOperationException()
        }

        override suspend fun getAllTodos(sessionId: String): Map<String?, List<TodoInfo>> = emptyMap()
    }
}
