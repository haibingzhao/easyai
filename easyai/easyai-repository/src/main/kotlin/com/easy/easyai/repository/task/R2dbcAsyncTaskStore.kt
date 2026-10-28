package com.easy.easyai.repository.task

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStateChanged
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.repository.database.Tables.AsyncTaskTable
import com.easy.easyai.repository.database.TransactionEvents
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
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
 *
 * Application isolation is a property of the account, not the row: [findOrphans] only loads tasks
 * whose user belongs to [host]'s application. Request-scoped reads and mutations are already
 * user-strict, and an account cannot belong to two applications.
 */
class R2dbcAsyncTaskStore(
    private val db: R2dbcDatabase,
    private val host: HostIdentity,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance,
    private val onChanged: (AsyncTaskStateChanged) -> Unit = {}
) : AsyncTaskStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        /** Statuses a task can still transition out of; every guarded mutator requires one of these. */
        private val NON_TERMINAL_STATUSES = listOf(AsyncTaskStatus.PENDING.name, AsyncTaskStatus.RUNNING.name)

        /** Insert in the caller's transaction, including an atomic schedule advance and batch enqueue. */
        internal suspend fun insertRow(task: AsyncTask, objectMapper: ObjectMapper): AsyncTask {
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
            return task
        }
    }

    override suspend fun create(task: AsyncTask): AsyncTask = suspendTransaction(db) {
        insertRow(task, objectMapper)
    }

    override suspend fun markRunning(id: String): AsyncTask? = suspendTransaction(db) {
        val now = System.currentTimeMillis()
        val claimed = AsyncTaskTable.update(where = {
            (AsyncTaskTable.id eq id) and (AsyncTaskTable.status eq AsyncTaskStatus.PENDING.name)
        }) {
            it[status] = AsyncTaskStatus.RUNNING.name
            it[startedAt] = now
            it[updatedAt] = now
        }
        if (claimed == 1) readRow(id) else null
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
        deleteRows(this, AsyncTaskTable.id eq id) > 0
    }

    override suspend fun deleteByUser(userId: String, status: AsyncTaskStatus?): Int =
        suspendTransaction(db) {
            var condition: Op<Boolean> = UserScope.filterStrict(AsyncTaskTable.userId, userId)
            status?.let { wanted -> condition = condition and (AsyncTaskTable.status eq wanted.name) }
            deleteRows(this, condition)
        }

    private suspend fun deleteRows(transaction: R2dbcTransaction, condition: Op<Boolean>): Int {
        val deletable = condition and ((AsyncTaskTable.type neq AsyncTaskType.WATCH.key) or
            (AsyncTaskTable.status inList AsyncTaskStatus.entries.filter { it.isTerminal }.map { it.name }))
        // Keep the routing payload locked until deletion; never infer removals from a bounded list.
        val rows = AsyncTaskTable.selectAll().where { deletable }
            .orderBy(AsyncTaskTable.id to SortOrder.ASC)
            .forUpdate()
            .toList()
        var deleted = 0
        for (row in rows) {
            val count = AsyncTaskTable.deleteWhere { (AsyncTaskTable.id eq row[AsyncTaskTable.id]) and deletable }
            deleted += count
            if (count > 0) {
                mapRow(row)?.let { task ->
                    TransactionEvents.afterCommit(transaction) { onChanged(AsyncTaskStateChanged(task, removed = true)) }
                }
            }
        }
        return deleted
    }

    /**
     * Non-terminal rows belonging to this application's accounts. Tasks of another application's
     * users, of `system`, or of a deleted account have no executor here and are not loaded.
     */
    override suspend fun findOrphans(): List<AsyncTask> = suspendTransaction(db) {
        AsyncTaskTable.selectAll()
            .where { nonTerminal() and UserScope.ownedByApp(AsyncTaskTable.userId, host.appName) }
            .mapNotNull { mapRow(it) }
            .toList()
    }

    /** Status filter matching rows that have not settled yet (PENDING or RUNNING). */
    private fun nonTerminal(): Op<Boolean> = AsyncTaskTable.status inList NON_TERMINAL_STATUSES

    /** Shared update-then-reread so every mutator returns the authoritative post-transition row. */
    private suspend fun transition(id: String, body: suspend (now: Long) -> Int): AsyncTask? =
        suspendTransaction(db) {
            val changed = body(System.currentTimeMillis())
            val task = readRow(id)
            if (changed > 0 && task != null && task.status.isTerminal) {
                TransactionEvents.afterCommit(this) { onChanged(AsyncTaskStateChanged(task)) }
            }
            task
        }

    internal suspend fun readRow(id: String): AsyncTask? =
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
    internal fun mapRow(row: ResultRow): AsyncTask? {
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
