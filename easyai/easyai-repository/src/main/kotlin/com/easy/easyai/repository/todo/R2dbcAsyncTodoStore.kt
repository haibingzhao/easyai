package com.easy.easyai.repository.todo

import com.easy.easyai.core.model.TodoInfo
import com.easy.easyai.core.model.TodoPriority
import com.easy.easyai.core.model.TodoStatus
import com.easy.easyai.repository.database.Tables.TodoTable
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.*
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory


/**
 * R2DBC-backed implementation of [AsyncTodoStore].
 * Uses full-replacement strategy: delete old rows, then batch-insert new ones.
 * Preserves createdAt timestamps across replacements.
 */
class R2dbcAsyncTodoStore(
    private val db: R2dbcDatabase
) : AsyncTodoStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun saveTodos(sessionId: String, agentRunId: String?, todos: List<TodoInfo>) {
        // Full-replacement strategy: atomically delete old rows and insert new ones.
        // This is intentional — the TodoManager normalizes the list before calling this method,
        // so we don't need incremental updates. The entire operation is wrapped in a single transaction.
        // Scope by agentRunId so parent/parallel sub-agents do not overwrite each other's todos.
        suspendTransaction(db) {
            // Preserve createdAt from existing records to maintain creation timestamps across replacements
            val oldCreatedAt = mutableMapOf<String, Long>()
            TodoTable.selectAll()
                .where { (TodoTable.sessionId eq sessionId) and scopeFilter(agentRunId) }
                .toList()
                .forEach { oldCreatedAt[it[TodoTable.id]] = it[TodoTable.createdAt] }

            // Delete all existing todos for this session scope
            TodoTable.deleteWhere { (TodoTable.sessionId eq sessionId) and scopeFilter(agentRunId) }

            // Insert new todos — position is already set by TodoManager.normalizeTodos,
            // but we re-assign here to ensure DB consistency with list order
            if (todos.isNotEmpty()) {
                TodoTable.batchInsert(todos) { todo ->
                    this[TodoTable.id] = todo.id
                    this[TodoTable.sessionId] = sessionId
                    this[TodoTable.agentRunId] = agentRunId
                    this[TodoTable.content] = todo.content
                    this[TodoTable.status] = todo.status.name.lowercase()
                    this[TodoTable.priority] = todo.priority.name.lowercase()
                    this[TodoTable.position] = todo.position
                    this[TodoTable.createdAt] = oldCreatedAt[todo.id] ?: todo.createdAt
                }
            }
        }
        logger.debug("Saved {} todos for session {} scope {}", todos.size, sessionId, agentRunId ?: "main")
    }

    override suspend fun getTodos(sessionId: String, agentRunId: String?): List<TodoInfo> {
        return suspendTransaction(db) {
            TodoTable.selectAll()
                .where { (TodoTable.sessionId eq sessionId) and scopeFilter(agentRunId) }
                .orderBy(TodoTable.position to SortOrder.ASC)
                .toList()
                .map { row ->
                    TodoInfo(
                        id = row[TodoTable.id],
                        content = row[TodoTable.content],
                        status = TodoStatus.valueOf(row[TodoTable.status].uppercase()),
                        priority = TodoPriority.valueOf(row[TodoTable.priority].uppercase()),
                        position = row[TodoTable.position],
                        createdAt = row[TodoTable.createdAt]
                    )
                }
        }
    }

    override suspend fun deleteTodos(sessionId: String, agentRunId: String?) {
        suspendTransaction(db) {
            TodoTable.deleteWhere { (TodoTable.sessionId eq sessionId) and scopeFilter(agentRunId) }
        }
        logger.debug("Deleted all todos for session {} scope {}", sessionId, agentRunId ?: "main")
    }

    override suspend fun deleteAllTodos(sessionId: String) {
        suspendTransaction(db) {
            TodoTable.deleteWhere { TodoTable.sessionId eq sessionId }
        }
        logger.debug("Deleted all todos for session {} across all scopes", sessionId)
    }

    override suspend fun getAllTodos(sessionId: String): Map<String?, List<TodoInfo>> {
        return suspendTransaction(db) {
            TodoTable.selectAll()
                .where { TodoTable.sessionId eq sessionId }
                .orderBy(TodoTable.position to SortOrder.ASC)
                .toList()
                .map { row ->
                    row[TodoTable.agentRunId] to TodoInfo(
                        id = row[TodoTable.id],
                        content = row[TodoTable.content],
                        status = TodoStatus.valueOf(row[TodoTable.status].uppercase()),
                        priority = TodoPriority.valueOf(row[TodoTable.priority].uppercase()),
                        position = row[TodoTable.position],
                        createdAt = row[TodoTable.createdAt]
                    )
                }
                .groupBy({ it.first }, { it.second })
        }
    }

    private fun scopeFilter(agentRunId: String?): Op<Boolean> =
        if (agentRunId == null) TodoTable.agentRunId.isNull() else TodoTable.agentRunId eq agentRunId
}
