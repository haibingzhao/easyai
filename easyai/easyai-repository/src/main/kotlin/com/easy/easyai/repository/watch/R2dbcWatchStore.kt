package com.easy.easyai.repository.watch

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchClaimPolicy
import com.easy.easyai.core.watch.WatchEnqueueResult
import com.easy.easyai.core.watch.WatchFinding
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchPendingAction
import com.easy.easyai.core.watch.WatchProperties
import com.easy.easyai.core.watch.WatchRunKind
import com.easy.easyai.core.watch.WatchSchedule
import com.easy.easyai.core.watch.WatchScheduleChanged
import com.easy.easyai.core.watch.WatchScheduleCursor
import com.easy.easyai.core.watch.WatchScheduleEntry
import com.easy.easyai.core.watch.WatchScheduleKind
import com.easy.easyai.core.watch.WatchService
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchStore
import com.easy.easyai.core.watch.WatchTag
import com.easy.easyai.core.watch.WatchTaskPayload
import com.easy.easyai.repository.database.Tables.AsyncTaskTable
import com.easy.easyai.repository.database.Tables.ScheduledTaskSettingTable
import com.easy.easyai.repository.database.Tables.WatchTable
import com.easy.easyai.repository.database.TransactionEvents
import com.easy.easyai.repository.database.UserScope
import com.easy.easyai.repository.task.R2dbcAsyncTaskStore
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.exists
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.neq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.R2dbcTransaction
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.util.UUID

/**
 * R2DBC implementation of [WatchStore].
 *
 * Admission serializes on the watch row and atomically advances its schedule, reserves the run slot
 * and inserts the task. Automatic admission locks the user's setting first; manual admission and
 * retries never acquire that lock after the watch. Handler writes retain their version/status guards.
 *
 * Ownership is strict per user ([UserScope.filterStrict]); the unscoped mutators are only reachable
 * after the service or handler checked the owner.
 *
 * Application isolation is a property of the account: the due scan ([schedulable]) only loads
 * watches whose user belongs to [host]'s application. Request-scoped listing and mutation are
 * already user-strict, and an account cannot belong to two applications.
 */
class R2dbcWatchStore(
    private val db: R2dbcDatabase,
    private val host: HostIdentity,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance,
    private val onChanged: (WatchScheduleChanged) -> Unit = {}
) : WatchStore {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val taskRows = R2dbcAsyncTaskStore(db, host, objectMapper)

    override suspend fun create(watch: Watch): Watch = suspendTransaction(db) {
        WatchTable.insert {
            it[id] = watch.id
            it[userId] = watch.userId
            it[title] = watch.title
            it[conditionText] = watch.condition
            it[mode] = watch.mode.name
            it[reminderText] = watch.reminderText
            it[scheduleKind] = watch.schedule.kind.name
            it[timezone] = watch.schedule.zone.id
            it[localTime] = watch.schedule.localTime?.toString()
            it[weekday] = watch.schedule.weekday?.name
            it[dayOfMonth] = watch.schedule.dayOfMonth
            it[anchorAt] = watch.schedule.anchorAt?.toEpochMilli()
            it[untilAt] = watch.untilAt?.toEpochMilli()
            it[nextDueAt] = watch.nextDueAt.toEpochMilli()
            it[status] = watch.status.name
            it[pendingAction] = watch.pendingAction.name
            it[version] = watch.version
            it[extensionCount] = watch.extensionCount
            it[tags] = objectMapper.writeValueAsString(watch.tags)
            it[completeOnMet] = watch.completeOnMet
            it[notifyPolicy] = watch.notifyPolicy.name
            it[notifyTags] = objectMapper.writeValueAsString(watch.notifyTags)
            it[sourceAgentId] = watch.sourceAgentId
            it[modelConfigId] = watch.modelConfigId
            it[projectId] = watch.projectId
            it[projectPath] = watch.projectPath
            it[projectKind] = watch.projectKind.name
            it[owners] = objectMapper.writeValueAsString(watch.owners)
            it[originSessionId] = watch.originSessionId
            it[runSessionId] = watch.runSessionId
            it[allowedTools] = objectMapper.writeValueAsString(watch.allowedTools)
            it[sourceHints] = watch.sourceHints
            it[lastTaskId] = watch.lastTaskId
            it[lastRunAt] = watch.lastRunAt
            it[lastFinding] = watch.lastFinding?.let { finding -> objectMapper.writeValueAsString(finding) }
            it[consecutiveFailures] = watch.consecutiveFailures
            it[lastError] = watch.lastError
            it[statusReason] = watch.statusReason
            it[createdAt] = watch.createdAt
            it[updatedAt] = watch.updatedAt
        }
        publishChanged(this, watch.id, watch.userId)
        watch
    }

    override suspend fun findById(id: String): Watch? = suspendTransaction(db) { readRow(id) }

    override suspend fun listByUser(
        userId: String,
        statuses: Set<WatchStatus>?,
        limit: Int
    ): List<Watch> = suspendTransaction(db) {
        var condition: Op<Boolean> = UserScope.filterStrict(WatchTable.userId, userId)
        statuses?.takeIf { it.isNotEmpty() }?.let { wanted ->
            condition = condition and (WatchTable.status inList wanted.map { it.name })
        }
        WatchTable.selectAll()
            .where { condition }
            .orderBy(WatchTable.createdAt to SortOrder.DESC)
            .limit(limit)
            .mapNotNull { it.toWatch() }
            .toList()
    }

    override suspend fun countActive(userId: String): Int = suspendTransaction(db) {
        WatchTable.selectAll()
            .where {
                UserScope.filterStrict(WatchTable.userId, userId) and
                    (WatchTable.status eq WatchStatus.ACTIVE.name)
            }
            .count()
            .toInt()
    }

    override suspend fun listUpcoming(
        horizon: Instant,
        cursor: WatchScheduleCursor?,
        limit: Int,
        userId: String?
    ): List<WatchScheduleEntry> {
        require(limit > 0) { "Scheduling page size must be positive" }
        return suspendTransaction(db) {
            var condition = schedulable() and (WatchTable.nextDueAt lessEq horizon.toEpochMilli())
            userId?.let { condition = condition and UserScope.filterStrict(WatchTable.userId, it) }
            cursor?.let {
                condition = condition and ((WatchTable.nextDueAt greater it.nextDueAt.toEpochMilli()) or
                    ((WatchTable.nextDueAt eq it.nextDueAt.toEpochMilli()) and (WatchTable.id greater it.watchId)))
            }
            scheduleQuery().where { condition }
                .orderBy(WatchTable.nextDueAt to SortOrder.ASC, WatchTable.id to SortOrder.ASC)
                .limit(limit)
                .mapNotNull { toSchedule(it) }
                .toList()
        }
    }

    override suspend fun findSchedule(watchId: String): WatchScheduleEntry? = suspendTransaction(db) {
        scheduleQuery().where { (WatchTable.id eq watchId) and schedulable() }
            .mapNotNull { toSchedule(it) }.toList().firstOrNull()
    }

    private fun scheduleQuery() = WatchTable.select(
        WatchTable.id, WatchTable.userId, WatchTable.version,
        WatchTable.nextDueAt, WatchTable.pendingAction
    )

    private fun schedulable(): Op<Boolean> = UserScope.ownedByApp(WatchTable.userId, host.appName) and
        (WatchTable.status eq WatchStatus.ACTIVE.name) and exists(
            ScheduledTaskSettingTable.select(ScheduledTaskSettingTable.userId).where {
                (ScheduledTaskSettingTable.userId eq WatchTable.userId) and
                    (ScheduledTaskSettingTable.taskType eq AsyncTaskType.WATCH.key) and
                    (ScheduledTaskSettingTable.enabled eq true)
            }
        )

    private fun toSchedule(row: ResultRow): WatchScheduleEntry? {
        val pending = WatchPendingAction.entries.firstOrNull { it.name == row[WatchTable.pendingAction] }
            ?: return null
        return WatchScheduleEntry(
            watchId = row[WatchTable.id],
            userId = row[WatchTable.userId],
            version = row[WatchTable.version],
            nextDueAt = Instant.ofEpochMilli(row[WatchTable.nextDueAt]),
            pendingAction = pending
        )
    }

    override suspend fun enqueueScheduled(
        expected: WatchScheduleEntry,
        now: Instant,
        properties: WatchProperties
    ): WatchEnqueueResult = suspendTransaction(db) {
        // Preference writes and automatic admission share this lock; never acquire it after a watch.
        val setting = ScheduledTaskSettingTable.selectAll().where {
            UserScope.filterStrict(ScheduledTaskSettingTable.userId, expected.userId) and
                (ScheduledTaskSettingTable.taskType eq AsyncTaskType.WATCH.key)
        }.forUpdate().toList().firstOrNull() ?: return@suspendTransaction WatchEnqueueResult.Stale
        if (!setting[ScheduledTaskSettingTable.enabled]) return@suspendTransaction WatchEnqueueResult.Stale
        val watch = lockWatch(expected.userId, expected.watchId) ?: return@suspendTransaction WatchEnqueueResult.Stale
        if (watch.status != WatchStatus.ACTIVE || watch.version != expected.version ||
            watch.nextDueAt != expected.nextDueAt || watch.pendingAction != expected.pendingAction ||
            watch.nextDueAt.isAfter(now)
        ) return@suspendTransaction WatchEnqueueResult.Stale
        firstInFlightId(watch)?.let { return@suspendTransaction WatchEnqueueResult.Busy(it) }
        val decision = WatchClaimPolicy.decide(watch, now, properties)
            ?: return@suspendTransaction WatchEnqueueResult.Stale
        val task = newTask(
            watch, decision.kind, watch.nextDueAt, now, TaskTrigger.SCHEDULE,
            setting[ScheduledTaskSettingTable.timeoutMs] ?: properties.runTimeoutMs
        )
        val updated = WatchTable.update({ WatchTable.id eq watch.id }) {
            it[nextDueAt] = decision.newNextDueAt.toEpochMilli()
            it[pendingAction] = decision.pendingAction.name
            if (decision.statusReason != null) it[statusReason] = decision.statusReason
            it[lastTaskId] = task.id
            it[updatedAt] = now.toEpochMilli()
        }
        if (updated == 0) return@suspendTransaction WatchEnqueueResult.Stale
        val persisted = R2dbcAsyncTaskStore.insertRow(task, objectMapper)
        publishChanged(this, watch.id, watch.userId)
        WatchEnqueueResult.Enqueued(persisted)
    }

    override suspend fun enqueueManual(
        userId: String,
        watchId: String,
        now: Instant,
        properties: WatchProperties,
        trigger: TaskTrigger
    ): WatchEnqueueResult = suspendTransaction(db) {
        val watch = lockWatch(userId, watchId) ?: return@suspendTransaction WatchEnqueueResult.Stale
        if (watch.status != WatchStatus.ACTIVE || watch.pendingAction != WatchPendingAction.CHECK) {
            return@suspendTransaction WatchEnqueueResult.Stale
        }
        firstInFlightId(watch)?.let { return@suspendTransaction WatchEnqueueResult.Busy(it) }
        val task = newTask(watch, WatchRunKind.CHECK, now, now, trigger, properties.runTimeoutMs)
        if (!reserveSlot(watch.id, task.id, now)) return@suspendTransaction WatchEnqueueResult.Stale
        val persisted = R2dbcAsyncTaskStore.insertRow(task, objectMapper)
        publishChanged(this, watch.id, watch.userId)
        WatchEnqueueResult.Enqueued(persisted)
    }

    override suspend fun retryRun(
        userId: String,
        watchId: String,
        taskId: String,
        expectedRetryCount: Int,
        now: Instant
    ): WatchEnqueueResult = suspendTransaction(db) {
        val watch = lockWatch(userId, watchId) ?: return@suspendTransaction WatchEnqueueResult.Stale
        if (watch.status != WatchStatus.ACTIVE) return@suspendTransaction WatchEnqueueResult.Stale
        val task = lockTask(taskId) ?: return@suspendTransaction WatchEnqueueResult.Stale
        if (!belongsToWatch(task, watch) || !task.status.isTerminal || task.retryCount != expectedRetryCount) {
            return@suspendTransaction WatchEnqueueResult.Stale
        }
        firstInFlightId(watch)?.let { return@suspendTransaction WatchEnqueueResult.Busy(it) }
        val updated = AsyncTaskTable.update({
            (AsyncTaskTable.id eq taskId) and
                (AsyncTaskTable.status eq task.status.name) and (AsyncTaskTable.retryCount eq expectedRetryCount)
        }) {
            it[status] = AsyncTaskStatus.PENDING.name
            it[error] = null
            it[result] = null
            it[startedAt] = null
            it[completedAt] = null
            it[retryCount] = expectedRetryCount + 1
            it[retriedAt] = now.toEpochMilli()
            it[updatedAt] = now.toEpochMilli()
        }
        if (updated == 0) return@suspendTransaction WatchEnqueueResult.Stale
        check(reserveSlot(watch.id, task.id, now)) { "Locked watch disappeared during retry" }
        val persisted = checkNotNull(taskRows.readRow(taskId)) { "Retried task disappeared" }
        publishChanged(this, watch.id, watch.userId)
        WatchEnqueueResult.Enqueued(persisted)
    }

    override suspend fun listInFlight(userId: String, watchId: String): List<AsyncTask> = suspendTransaction(db) {
        val watch = readRow(watchId) ?: return@suspendTransaction emptyList()
        if (watch.userId != userId) return@suspendTransaction emptyList()
        AsyncTaskTable.selectAll().where { inFlight(watch) }
            .orderBy(AsyncTaskTable.createdAt to SortOrder.ASC, AsyncTaskTable.id to SortOrder.ASC)
            .mapNotNull { taskRows.mapRow(it) }.toList()
    }

    override suspend fun claimRunSlot(task: AsyncTask, expectedVersion: Int, at: Instant): Boolean =
        suspendTransaction(db) {
            val watchId = WatchTaskPayload.watchIdOf(task) ?: return@suspendTransaction false
            val watch = lockWatch(task.userId, watchId) ?: return@suspendTransaction false
            if (watch.status != WatchStatus.ACTIVE || watch.version != expectedVersion || !belongsToWatch(task, watch)) {
                return@suspendTransaction false
            }
            val actual = lockTask(task.id) ?: return@suspendTransaction false
            if (actual.status != AsyncTaskStatus.RUNNING || !belongsToWatch(actual, watch)) {
                return@suspendTransaction false
            }
            if (firstInFlightId(watch, task.id) != null) return@suspendTransaction false
            // A legacy unreserved task may take a free/settled slot, never somebody else's live slot.
            val previous = watch.lastTaskId
            if (previous != null && previous != task.id) {
                val liveSlot = AsyncTaskTable.select(AsyncTaskTable.id).where {
                    (AsyncTaskTable.id eq previous) and taskNonTerminal()
                }.toList().isNotEmpty()
                if (liveSlot) return@suspendTransaction false
            }
            reserveSlot(watch.id, task.id, at)
        }

    private suspend fun lockWatch(userId: String, watchId: String): Watch? = WatchTable.selectAll().where {
        (WatchTable.id eq watchId) and UserScope.filterStrict(WatchTable.userId, userId)
    }.forUpdate().mapNotNull { it.toWatch() }.toList().firstOrNull()

    private suspend fun lockTask(taskId: String): AsyncTask? = AsyncTaskTable.selectAll()
        .where { AsyncTaskTable.id eq taskId }.forUpdate().mapNotNull { taskRows.mapRow(it) }.toList().firstOrNull()

    private fun belongsToWatch(task: AsyncTask, watch: Watch): Boolean =
        task.userId == watch.userId && task.type == AsyncTaskType.WATCH &&
            task.sessionId == watch.runSessionId && WatchTaskPayload.watchIdOf(task) == watch.id

    private fun taskNonTerminal(): Op<Boolean> = AsyncTaskTable.status inList
        listOf(AsyncTaskStatus.PENDING.name, AsyncTaskStatus.RUNNING.name)

    private fun inFlight(watch: Watch): Op<Boolean> =
        UserScope.filterStrict(AsyncTaskTable.userId, watch.userId) and
            (AsyncTaskTable.sessionId eq watch.runSessionId) and (AsyncTaskTable.type eq AsyncTaskType.WATCH.key) and
            taskNonTerminal()

    private suspend fun firstInFlightId(watch: Watch, exceptTaskId: String? = null): String? {
        var condition = inFlight(watch)
        exceptTaskId?.let { condition = condition and (AsyncTaskTable.id neq it) }
        // Filter before limiting, and do not decode payload JSON to decide whether a task is live.
        return AsyncTaskTable.select(AsyncTaskTable.id).where { condition }
            .orderBy(AsyncTaskTable.createdAt to SortOrder.ASC, AsyncTaskTable.id to SortOrder.ASC)
            .limit(1).map { it[AsyncTaskTable.id] }.toList().firstOrNull()
    }

    private suspend fun reserveSlot(watchId: String, taskId: String, now: Instant): Boolean =
        WatchTable.update({ WatchTable.id eq watchId }) {
            it[lastTaskId] = taskId
            it[updatedAt] = now.toEpochMilli()
        } > 0

    private fun newTask(
        watch: Watch,
        kind: WatchRunKind,
        scheduledFor: Instant,
        now: Instant,
        trigger: TaskTrigger,
        timeoutMs: Long
    ) = AsyncTask(
        id = UUID.randomUUID().toString(),
        userId = watch.userId,
        type = AsyncTaskType.WATCH,
        trigger = trigger,
        title = WatchService.taskTitle(watch),
        sessionId = watch.runSessionId,
        timeoutMs = timeoutMs,
        payload = mapOf(
            WatchTaskPayload.KEY_WATCH_ID to watch.id,
            WatchTaskPayload.KEY_KIND to kind.name,
            WatchTaskPayload.KEY_SCHEDULED_FOR to scheduledFor.toEpochMilli(),
            WatchTaskPayload.KEY_DEFINITION_VERSION to watch.version,
            WatchTaskPayload.KEY_TITLE to watch.title
        ),
        createdAt = now.toEpochMilli(),
        updatedAt = now.toEpochMilli()
    )

    override suspend fun applyFinding(
        id: String,
        expectedVersion: Int,
        expectedStatus: WatchStatus,
        status: WatchStatus,
        pendingAction: WatchPendingAction,
        nextDueAt: Long,
        finding: WatchFinding,
        taskId: String,
        at: Long
    ): Watch? = suspendTransaction(db) {
        val updated = WatchTable.update(where = {
            (WatchTable.id eq id) and
                (WatchTable.version eq expectedVersion) and
                (WatchTable.status eq expectedStatus.name) and
                // The task that most recently claimed the run is the only one allowed to settle it.
                // This is the race guard for a manual "check now" slipping in beside a scheduled run.
                (WatchTable.lastTaskId eq taskId)
        }) {
            it[WatchTable.status] = status.name
            it[WatchTable.pendingAction] = pendingAction.name
            it[WatchTable.nextDueAt] = nextDueAt
            it[lastFinding] = objectMapper.writeValueAsString(finding)
            it[lastTaskId] = taskId
            it[lastRunAt] = at
            it[consecutiveFailures] = 0
            it[lastError] = null
            it[WatchTable.statusReason] = null
            it[updatedAt] = at
        }
        if (updated == 0) null else changedRow(this, id)
    }

    override suspend fun recordFailure(
        id: String,
        expectedVersion: Int,
        expectedStatus: WatchStatus,
        error: String,
        nextDueAt: Long,
        status: WatchStatus,
        consecutiveFailures: Int,
        statusReason: String?,
        taskId: String?,
        at: Long
    ): Watch? = suspendTransaction(db) {
        // Same race guard as applyFinding: a failure belongs to the run that last claimed the row, so
        // a superseded task cannot overwrite the state of a newer one.
        val claimedByCaller = if (taskId == null) Op.TRUE else (WatchTable.lastTaskId eq taskId)
        val updated = WatchTable.update(where = {
            (WatchTable.id eq id) and
                (WatchTable.version eq expectedVersion) and
                (WatchTable.status eq expectedStatus.name) and
                claimedByCaller
        }) {
            it[WatchTable.status] = status.name
            it[WatchTable.nextDueAt] = nextDueAt
            it[WatchTable.consecutiveFailures] = consecutiveFailures
            it[lastError] = error
            it[WatchTable.statusReason] = statusReason
            if (taskId != null) it[lastTaskId] = taskId
            it[lastRunAt] = at
            it[updatedAt] = at
        }
        if (updated == 0) null else changedRow(this, id)
    }

    override suspend fun applyExpiryReview(
        id: String,
        expectedVersion: Int,
        expectedPendingAction: WatchPendingAction,
        status: WatchStatus,
        pendingAction: WatchPendingAction,
        untilAt: Long?,
        anchorAt: Long?,
        nextDueAt: Long,
        extensionCount: Int,
        statusReason: String?,
        taskId: String,
        at: Long
    ): Watch? = suspendTransaction(db) {
        val updated = WatchTable.update(where = {
            (WatchTable.id eq id) and
                (WatchTable.version eq expectedVersion) and
                (WatchTable.pendingAction eq expectedPendingAction.name) and
                (WatchTable.status eq WatchStatus.ACTIVE.name) and
                (WatchTable.lastTaskId eq taskId)
        }) {
            it[WatchTable.status] = status.name
            it[WatchTable.pendingAction] = pendingAction.name
            it[WatchTable.untilAt] = untilAt
            it[WatchTable.anchorAt] = anchorAt
            it[WatchTable.nextDueAt] = nextDueAt
            it[WatchTable.extensionCount] = extensionCount
            it[WatchTable.statusReason] = statusReason
            it[lastTaskId] = taskId
            it[lastRunAt] = at
            it[consecutiveFailures] = 0
            it[lastError] = null
            it[updatedAt] = at
        }
        if (updated == 0) null else changedRow(this, id)
    }

    override suspend fun updateDefinition(
        watch: Watch,
        expectedVersion: Int,
        expectedStatus: WatchStatus
    ): Watch? =
        suspendTransaction(db) {
            val updated = WatchTable.update(where = {
                (WatchTable.id eq watch.id) and
                    UserScope.filterStrict(WatchTable.userId, watch.userId) and
                    (WatchTable.version eq expectedVersion) and
                    (WatchTable.status eq expectedStatus.name)
            }) {
                it[title] = watch.title
                it[conditionText] = watch.condition
                it[mode] = watch.mode.name
                it[reminderText] = watch.reminderText
                it[scheduleKind] = watch.schedule.kind.name
                it[timezone] = watch.schedule.zone.id
                it[localTime] = watch.schedule.localTime?.toString()
                it[weekday] = watch.schedule.weekday?.name
                it[dayOfMonth] = watch.schedule.dayOfMonth
                it[anchorAt] = watch.schedule.anchorAt?.toEpochMilli()
                it[untilAt] = watch.untilAt?.toEpochMilli()
                it[nextDueAt] = watch.nextDueAt.toEpochMilli()
                it[status] = watch.status.name
                it[pendingAction] = watch.pendingAction.name
                it[version] = watch.version
                it[extensionCount] = watch.extensionCount
                it[tags] = objectMapper.writeValueAsString(watch.tags)
                it[completeOnMet] = watch.completeOnMet
                it[notifyPolicy] = watch.notifyPolicy.name
                it[notifyTags] = objectMapper.writeValueAsString(watch.notifyTags)
                it[allowedTools] = objectMapper.writeValueAsString(watch.allowedTools)
                it[sourceHints] = watch.sourceHints
                it[consecutiveFailures] = watch.consecutiveFailures
                it[lastError] = watch.lastError
                it[statusReason] = watch.statusReason
                it[updatedAt] = watch.updatedAt
            }
            if (updated == 0) null else changedRow(this, watch.id)
        }

    override suspend fun setStatus(
        id: String,
        status: WatchStatus,
        statusReason: String?,
        pendingAction: WatchPendingAction?,
        nextDueAt: Long?,
        expectedStatus: WatchStatus?,
        clearFailures: Boolean
    ): Watch? = suspendTransaction(db) {
        val condition = if (expectedStatus == null) {
            WatchTable.id eq id
        } else {
            (WatchTable.id eq id) and (WatchTable.status eq expectedStatus.name)
        }
        val updated = WatchTable.update(where = { condition }) {
            it[WatchTable.status] = status.name
            it[WatchTable.statusReason] = statusReason
            if (pendingAction != null) it[WatchTable.pendingAction] = pendingAction.name
            if (nextDueAt != null) it[WatchTable.nextDueAt] = nextDueAt
            if (clearFailures) {
                it[consecutiveFailures] = 0
                it[lastError] = null
            }
            it[updatedAt] = System.currentTimeMillis()
        }
        if (updated == 0) null else changedRow(this, id)
    }

    override suspend fun delete(id: String): Boolean = suspendTransaction(db) {
        val row = WatchTable.select(WatchTable.userId)
            .where { WatchTable.id eq id }.forUpdate().toList().firstOrNull()
            ?: return@suspendTransaction false
        val deleted = WatchTable.deleteWhere { WatchTable.id eq id } > 0
        if (deleted) publishChanged(this, id, row[WatchTable.userId])
        deleted
    }

    private suspend fun changedRow(transaction: R2dbcTransaction, id: String): Watch? {
        // A successful state write still invalidates the timer when unrelated JSON is unreadable.
        val row = WatchTable.select(WatchTable.userId).where { WatchTable.id eq id }.toList().firstOrNull()
        if (row != null) publishChanged(transaction, id, row[WatchTable.userId])
        return readRow(id)
    }

    private fun publishChanged(transaction: R2dbcTransaction, watchId: String, userId: String) {
        val event = WatchScheduleChanged(watchId, userId)
        TransactionEvents.afterCommit(transaction) {
            runCatching { onChanged(event) }.onFailure {
                logger.warn("Failed to publish schedule change for watch {}", watchId, it)
            }
        }
    }

    private suspend fun readRow(id: String): Watch? =
        WatchTable.selectAll()
            .where { WatchTable.id eq id }
            .limit(1)
            .mapNotNull { it.toWatch() }
            .toList()
            .firstOrNull()

    /**
     * Map a row, or null when a stored enum/time zone/JSON value is unreadable. One corrupt row must
     * not take down a list; the warning names the row so it can be found.
     */
    private fun ResultRow.toWatch(): Watch? {
        val watchId = this[WatchTable.id]
        return try {
            Watch(
                id = watchId,
                userId = this[WatchTable.userId],
                title = this[WatchTable.title],
                condition = this[WatchTable.conditionText],
                mode = enumOf<WatchMode>(this[WatchTable.mode], "mode", watchId),
                reminderText = this[WatchTable.reminderText],
                schedule = WatchSchedule(
                    kind = enumOf<WatchScheduleKind>(this[WatchTable.scheduleKind], "schedule kind", watchId),
                    zone = ZoneId.of(this[WatchTable.timezone]),
                    localTime = this[WatchTable.localTime]?.let { LocalTime.parse(it) },
                    weekday = this[WatchTable.weekday]?.let { DayOfWeek.valueOf(it) },
                    dayOfMonth = this[WatchTable.dayOfMonth],
                    anchorAt = this[WatchTable.anchorAt]?.let { Instant.ofEpochMilli(it) }
                ),
                untilAt = this[WatchTable.untilAt]?.let { Instant.ofEpochMilli(it) },
                nextDueAt = Instant.ofEpochMilli(this[WatchTable.nextDueAt]),
                status = enumOf<WatchStatus>(this[WatchTable.status], "status", watchId),
                pendingAction = enumOf<WatchPendingAction>(
                    this[WatchTable.pendingAction], "pending action", watchId
                ),
                version = this[WatchTable.version],
                extensionCount = this[WatchTable.extensionCount],
                tags = readTags(this[WatchTable.tags], watchId),
                completeOnMet = this[WatchTable.completeOnMet],
                notifyPolicy = enumOf<WatchNotifyPolicy>(this[WatchTable.notifyPolicy], "notify policy", watchId),
                notifyTags = readStringSet(this[WatchTable.notifyTags], watchId),
                sourceAgentId = this[WatchTable.sourceAgentId],
                modelConfigId = this[WatchTable.modelConfigId],
                projectId = this[WatchTable.projectId],
                projectPath = this[WatchTable.projectPath],
                projectKind = ProjectKind.entries.firstOrNull { it.name == this[WatchTable.projectKind] }
                    ?: ProjectKind.USER,
                owners = readStringSet(this[WatchTable.owners], watchId),
                originSessionId = this[WatchTable.originSessionId],
                runSessionId = this[WatchTable.runSessionId],
                allowedTools = readStringList(this[WatchTable.allowedTools], watchId),
                sourceHints = this[WatchTable.sourceHints],
                lastTaskId = this[WatchTable.lastTaskId],
                lastRunAt = this[WatchTable.lastRunAt],
                lastFinding = readFinding(this[WatchTable.lastFinding], watchId),
                consecutiveFailures = this[WatchTable.consecutiveFailures],
                lastError = this[WatchTable.lastError],
                statusReason = this[WatchTable.statusReason],
                createdAt = this[WatchTable.createdAt],
                updatedAt = this[WatchTable.updatedAt]
            )
        } catch (e: Exception) {
            logger.warn("Unreadable watch row {}: {}", watchId, e.message)
            null
        }
    }

    private fun readTags(raw: String?, watchId: String): List<WatchTag> =
        readJson(raw, object : TypeReference<List<WatchTag>>() {}, "tags", watchId) ?: emptyList()

    private fun readStringList(raw: String?, watchId: String): List<String> =
        readJson(raw, object : TypeReference<List<String>>() {}, "string list", watchId) ?: emptyList()

    private fun readStringSet(raw: String?, watchId: String): Set<String> =
        readJson(raw, object : TypeReference<Set<String>>() {}, "string set", watchId) ?: emptySet()

    private fun readFinding(raw: String?, watchId: String): WatchFinding? =
        readJson(raw, object : TypeReference<WatchFinding>() {}, "last finding", watchId)

    private fun <T> readJson(raw: String?, type: TypeReference<T>, label: String, watchId: String): T? {
        if (raw.isNullOrBlank()) return null
        return runCatching { objectMapper.readValue(raw, type) }
            .onFailure { logger.warn("Unreadable {} on watch {}: {}", label, watchId, it.message) }
            .getOrNull()
    }

    private inline fun <reified T : Enum<T>> enumOf(raw: String, label: String, watchId: String): T =
        enumValues<T>().firstOrNull { it.name == raw }
            ?: throw IllegalStateException("Unknown $label '$raw' on watch $watchId")
}
