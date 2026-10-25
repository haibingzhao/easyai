package com.easy.easyai.repository.task

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.repository.database.Tables.AsyncTaskTable
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * R2DBC implementation of [AsyncTaskStore] over the `async_task` table.
 *
 * Ownership is strict per user ([UserScope.filterStrict]) — no shared `system` fold-in — so the
 * scoped list/delete methods never leak another user's tasks. `payload`/`result` are stored as JSON
 * text. Mutators re-read the row inside the same transaction so callers get the authoritative
 * post-transition [AsyncTask] (used for `durationMs` and event payloads).
 */
class R2dbcAsyncTaskStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : AsyncTaskStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    private companion object {
        /** Statuses a task can still transition out of; every guarded mutator requires one of these. */
        val NON_TERMINAL_STATUSES = listOf(AsyncTaskStatus.PENDING.name, AsyncTaskStatus.RUNNING.name)
    }

    override suspend fun create(task: AsyncTask): AsyncTask = suspendTransaction(db) {
        AsyncTaskTable.insert {
            it[id] = task.id
            it[userId] = task.userId
            it[type] = task.type.key
            it[status] = task.status.name
            it[triggerSource] = task.trigger.name
            it[sessionId] = task.sessionId
            it[title] = task.title
            it[payload] = task.payload.takeIf { p -> p.isNotEmpty() }?.let { p -> objectMapper.writeValueAsString(p) }
            it[result] = task.result?.let { r -> objectMapper.writeValueAsString(r) }
            it[error] = task.error
            it[timeoutMs] = task.timeoutMs
            it[retryCount] = task.retryCount
            it[retriedAt] = task.retriedAt
            it[createdAt] = task.createdAt
            it[startedAt] = task.startedAt
            it[completedAt] = task.completedAt
            it[updatedAt] = task.updatedAt
        }
        task
    }

    override suspend fun markRunning(id: String): AsyncTask? = transition(id) { now ->
        // Only PENDING may start; guards against a late dispatch reviving an already-settled row.
        AsyncTaskTable.update(where = { (AsyncTaskTable.id eq id) and (AsyncTaskTable.status eq AsyncTaskStatus.PENDING.name) }) {
            it[status] = AsyncTaskStatus.RUNNING.name
            it[startedAt] = now
            it[updatedAt] = now
        }
    }

    override suspend fun complete(id: String, result: Map<String, Any?>?): AsyncTask? = transition(id) { now ->
        AsyncTaskTable.update(where = { (AsyncTaskTable.id eq id) and nonTerminal() }) {
            it[status] = AsyncTaskStatus.COMPLETED.name
            it[this.result] = result?.let { r -> objectMapper.writeValueAsString(r) }
            it[error] = null
            it[completedAt] = now
            it[updatedAt] = now
        }
    }

    override suspend fun fail(id: String, error: String): AsyncTask? = transition(id) { now ->
        AsyncTaskTable.update(where = { (AsyncTaskTable.id eq id) and nonTerminal() }) {
            it[status] = AsyncTaskStatus.FAILED.name
            it[this.error] = error
            it[completedAt] = now
            it[updatedAt] = now
        }
    }

    override suspend fun markTimedOut(id: String, error: String): AsyncTask? = transition(id) { now ->
        AsyncTaskTable.update(where = { (AsyncTaskTable.id eq id) and nonTerminal() }) {
            it[status] = AsyncTaskStatus.TIMED_OUT.name
            it[this.error] = error
            it[completedAt] = now
            it[updatedAt] = now
        }
    }

    override suspend fun cancel(id: String): AsyncTask? = transition(id) { now ->
        // Never overwrite a terminal row: a task that just COMPLETED must not flip to CANCELLED.
        AsyncTaskTable.update(where = { (AsyncTaskTable.id eq id) and nonTerminal() }) {
            it[status] = AsyncTaskStatus.CANCELLED.name
            it[completedAt] = now
            it[updatedAt] = now
        }
    }

    override suspend fun markInterrupted(id: String): AsyncTask? = transition(id) { now ->
        AsyncTaskTable.update(where = { (AsyncTaskTable.id eq id) and nonTerminal() }) {
            it[status] = AsyncTaskStatus.INTERRUPTED.name
            it[updatedAt] = now
        }
    }

    override suspend fun updateSessionId(id: String, sessionId: String): AsyncTask? = transition(id) { now ->
        AsyncTaskTable.update(where = { (AsyncTaskTable.id eq id) and nonTerminal() }) {
            it[this.sessionId] = sessionId
            it[updatedAt] = now
        }
    }

    override suspend fun retry(id: String): AsyncTask? = suspendTransaction(db) {
        val current = readRow(id) ?: return@suspendTransaction null
        val now = System.currentTimeMillis()
        AsyncTaskTable.update(where = { AsyncTaskTable.id eq id }) {
            it[status] = AsyncTaskStatus.PENDING.name
            it[error] = null
            it[result] = null
            it[startedAt] = null
            it[completedAt] = null
            it[retryCount] = current.retryCount + 1
            it[retriedAt] = now
            it[updatedAt] = now
        }
        readRow(id)
    }

    override suspend fun findById(id: String): AsyncTask? = suspendTransaction(db) { readRow(id) }

    override suspend fun listByUser(
        userId: String,
        type: AsyncTaskType?,
        status: AsyncTaskStatus?,
        sessionId: String?,
        limit: Int
    ): List<AsyncTask> = suspendTransaction(db) {
        var condition: Op<Boolean> = UserScope.filterStrict(AsyncTaskTable.userId, userId)
        type?.let { condition = condition and (AsyncTaskTable.type eq it.key) }
        status?.let { condition = condition and (AsyncTaskTable.status eq it.name) }
        sessionId?.let { condition = condition and (AsyncTaskTable.sessionId eq it) }
        AsyncTaskTable.selectAll()
            .where { condition }
            .orderBy(AsyncTaskTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .mapNotNull { mapRow(it) }
            .toList()
    }

    override suspend fun listNonTerminal(userId: String, type: AsyncTaskType?, limit: Int): List<AsyncTask> =
        suspendTransaction(db) {
            var condition: Op<Boolean> = UserScope.filterStrict(AsyncTaskTable.userId, userId) and nonTerminal()
            type?.let { condition = condition and (AsyncTaskTable.type eq it.key) }
            AsyncTaskTable.selectAll()
                .where { condition }
                .orderBy(AsyncTaskTable.createdAt to SortOrder.DESC)
                .limit(limit)
                .mapNotNull { mapRow(it) }
                .toList()
        }

    override suspend fun delete(id: String): Boolean = suspendTransaction(db) {
        AsyncTaskTable.deleteWhere { AsyncTaskTable.id eq id } > 0
    }

    override suspend fun deleteByUser(userId: String, status: AsyncTaskStatus?): Int = suspendTransaction(db) {
        AsyncTaskTable.deleteWhere {
            if (status == null) {
                UserScope.filterStrict(AsyncTaskTable.userId, userId)
            } else {
                UserScope.filterStrict(AsyncTaskTable.userId, userId) and (AsyncTaskTable.status eq status.name)
            }
        }
    }

    override suspend fun findOrphans(): List<AsyncTask> = suspendTransaction(db) {
        AsyncTaskTable.selectAll()
            .where { nonTerminal() }
            .mapNotNull { mapRow(it) }
            .toList()
    }

    /** Status filter matching rows that have not settled yet (PENDING or RUNNING). */
    private fun nonTerminal(): Op<Boolean> = AsyncTaskTable.status inList NON_TERMINAL_STATUSES

    /** Shared update-then-reread so every mutator returns the authoritative post-transition row. */
    private suspend fun transition(id: String, body: suspend (now: Long) -> Unit): AsyncTask? =
        suspendTransaction(db) {
            body(System.currentTimeMillis())
            readRow(id)
        }

    private suspend fun readRow(id: String): AsyncTask? =
        AsyncTaskTable.selectAll()
            .where { AsyncTaskTable.id eq id }
            .limit(1)
            .mapNotNull { mapRow(it) }
            .toList()
            .firstOrNull()

    /**
     * Map a row, or null when a status/trigger column carries a value this build does not know. The
     * type is an open key now, so a host-defined or newer type still lists (with a generic label in
     * the frontend). Status and trigger stay closed: this build cannot transition a row whose status
     * it cannot name, and hiding one row beats failing the list for every other row.
     */
    private fun mapRow(row: ResultRow): AsyncTask? {
        val taskId = row[AsyncTaskTable.id]
        val type = AsyncTaskType(row[AsyncTaskTable.type])
        val statusName = row[AsyncTaskTable.status]
        val status = enumOrNull<AsyncTaskStatus>(statusName) ?: return unreadableRow(taskId, "status", statusName)
        val triggerName = row[AsyncTaskTable.triggerSource]
        val trigger = enumOrNull<TaskTrigger>(triggerName) ?: return unreadableRow(taskId, "trigger", triggerName)
        return AsyncTask(
            id = taskId,
            userId = row[AsyncTaskTable.userId],
            type = type,
            trigger = trigger,
            title = row[AsyncTaskTable.title],
            status = status,
            sessionId = row[AsyncTaskTable.sessionId],
            payload = row[AsyncTaskTable.payload]?.let { json -> parseJsonObject(taskId, "payload", json) }
                ?: emptyMap(),
            result = row[AsyncTaskTable.result]?.let { json -> parseJsonObject(taskId, "result", json) },
            error = row[AsyncTaskTable.error],
            timeoutMs = row[AsyncTaskTable.timeoutMs],
            retryCount = row[AsyncTaskTable.retryCount],
            retriedAt = row[AsyncTaskTable.retriedAt],
            createdAt = row[AsyncTaskTable.createdAt],
            startedAt = row[AsyncTaskTable.startedAt],
            completedAt = row[AsyncTaskTable.completedAt],
            updatedAt = row[AsyncTaskTable.updatedAt]
        )
    }

    /** Status and trigger are written as enum names, so a stored name this build lacks is unreadable. */
    private inline fun <reified T : Enum<T>> enumOrNull(name: String): T? =
        enumValues<T>().firstOrNull { it.name == name }

    /**
     * Read a JSON text column, or null when this build cannot parse it. Unlike an unknown enum, a bad
     * payload/result costs only the per-type title or the summary line — so the row stays listed and
     * cancellable instead of being hidden with it.
     */
    private fun parseJsonObject(taskId: String, column: String, json: String): Map<String, Any?>? =
        runCatching { objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {}) }
            .onFailure {
                logger.warn(
                    "Async task {} has unreadable {} JSON, listing it without: {}", taskId, column, it.message
                )
            }
            .getOrNull()

    private fun unreadableRow(taskId: String, column: String, value: String?): AsyncTask? {
        logger.warn(
            "Skipping async task {} with unknown {} '{}' (newer writer or rollback?)", taskId, column, value
        )
        return null
    }
}
