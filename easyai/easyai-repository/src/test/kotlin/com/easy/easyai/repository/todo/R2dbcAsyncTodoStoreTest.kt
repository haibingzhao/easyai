package com.easy.easyai.repository.todo

import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.model.TodoPriority
import com.easy.easyai.core.model.TodoStatus
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration tests for [R2dbcAsyncTodoStore] scoped todo isolation.
 * Uses an in-memory H2 R2DBC database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcAsyncTodoStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///todo_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore() = R2dbcAsyncTodoStore(db)

    private fun todo(content: String, status: TodoStatus) = TodoInfo(
        id = UUID.randomUUID().toString(),
        content = content,
        status = status,
        priority = TodoPriority.MEDIUM,
        position = 0,
        createdAt = System.currentTimeMillis()
    )

    @Nested
    inner class `scoped save and get` {

        @Test
        fun `main agent and sub-agent todos are isolated by agentRunId`() = runTest {
            val store = createStore()
            val sessionId = "session-${UUID.randomUUID()}"
            val agentRunId = "run-${UUID.randomUUID()}"

            val mainTodo = todo("main task", TodoStatus.IN_PROGRESS)
            val subTodo = todo("sub task", TodoStatus.PENDING)

            store.saveTodos(sessionId, agentRunId = null, listOf(mainTodo))
            store.saveTodos(sessionId, agentRunId = agentRunId, listOf(subTodo))

            assertEquals(listOf(mainTodo.content), store.getTodos(sessionId, null).map { it.content })
            assertEquals(listOf(subTodo.content), store.getTodos(sessionId, agentRunId).map { it.content })
        }

        @Test
        fun `saveTodos replaces only the current scope`() = runTest {
            val store = createStore()
            val sessionId = "session-${UUID.randomUUID()}"
            val agentRunId = "run-${UUID.randomUUID()}"

            store.saveTodos(sessionId, agentRunId = null, listOf(todo("main-1", TodoStatus.COMPLETED)))
            store.saveTodos(sessionId, agentRunId = agentRunId, listOf(todo("sub-1", TodoStatus.COMPLETED)))
            store.saveTodos(sessionId, agentRunId = null, listOf(todo("main-2", TodoStatus.PENDING)))

            assertEquals(listOf("main-2"), store.getTodos(sessionId, null).map { it.content })
            assertEquals(listOf("sub-1"), store.getTodos(sessionId, agentRunId).map { it.content })
        }

        @Test
        fun `deleteTodos removes only the targeted scope`() = runTest {
            val store = createStore()
            val sessionId = "session-${UUID.randomUUID()}"
            val agentRunId = "run-${UUID.randomUUID()}"

            store.saveTodos(sessionId, agentRunId = null, listOf(todo("main", TodoStatus.PENDING)))
            store.saveTodos(sessionId, agentRunId = agentRunId, listOf(todo("sub", TodoStatus.PENDING)))

            store.deleteTodos(sessionId, agentRunId)

            assertEquals(listOf("main"), store.getTodos(sessionId, null).map { it.content })
            assertTrue(store.getTodos(sessionId, agentRunId).isEmpty())
        }

        @Test
        fun `deleteAllTodos removes all scopes for a session`() = runTest {
            val store = createStore()
            val sessionId = "session-${UUID.randomUUID()}"
            val agentRunId = "run-${UUID.randomUUID()}"

            store.saveTodos(sessionId, agentRunId = null, listOf(todo("main", TodoStatus.PENDING)))
            store.saveTodos(sessionId, agentRunId = agentRunId, listOf(todo("sub", TodoStatus.PENDING)))

            store.deleteAllTodos(sessionId)

            assertTrue(store.getTodos(sessionId, null).isEmpty())
            assertTrue(store.getTodos(sessionId, agentRunId).isEmpty())
        }
    }
}
