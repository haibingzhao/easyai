package com.easy.easyai.repository.watch

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchFinding
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchPendingAction
import com.easy.easyai.core.watch.WatchSchedule
import com.easy.easyai.core.watch.WatchScheduleKind
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchStore
import com.easy.easyai.core.watch.WatchTag
import com.easy.easyai.repository.database.Tables.WatchTable
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
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

/**
 * R2DBC implementation of [WatchStore].
 *
 * Every mutator is guarded on the values it read: the planner claims a due row only while
 * `next_due_at` is still the instant it saw, and the handler applies a finding only while `version`
 * and status are unchanged. A guarded-out write returns null, which the caller reports as stale
 * rather than retrying — a user edit always wins over an in-flight run.
 *
 * Ownership is strict per user ([UserScope.filterStrict]); the unscoped mutators are only reachable
 * after the service or handler checked the owner.
 */
class R2dbcWatchStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : WatchStore {

    private val logger = LoggerFactory.getLogger(javaClass)

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

    override suspend fun listDue(userId: String, now: Long, limit: Int): List<Watch> = suspendTransaction(db) {
        WatchTable.selectAll()
            .where {
                UserScope.filterStrict(WatchTable.userId, userId) and
                    (WatchTable.status eq WatchStatus.ACTIVE.name) and
                    (WatchTable.nextDueAt lessEq now)
            }
            .orderBy(WatchTable.nextDueAt to SortOrder.ASC)
            .limit(limit)
            .mapNotNull { it.toWatch() }
            .toList()
    }

    override suspend fun claim(
        id: String,
        expectedNextDueAt: Long,
        newNextDueAt: Long,
        pendingAction: WatchPendingAction,
        newStatus: WatchStatus?,
        statusReason: String?
    ): Boolean = suspendTransaction(db) {
        val now = System.currentTimeMillis()
        WatchTable.update(where = {
            (WatchTable.id eq id) and
                (WatchTable.nextDueAt eq expectedNextDueAt) and
                (WatchTable.status eq WatchStatus.ACTIVE.name)
        }) {
            it[WatchTable.nextDueAt] = newNextDueAt
            it[WatchTable.pendingAction] = pendingAction.name
            it[updatedAt] = now
            if (newStatus != null) it[WatchTable.status] = newStatus.name
            if (statusReason != null) it[WatchTable.statusReason] = statusReason
        } > 0
    }

    override suspend fun markRunStarted(id: String, taskId: String, at: Long): Boolean =
        suspendTransaction(db) {
            WatchTable.update(where = { WatchTable.id eq id }) {
                it[lastTaskId] = taskId
                it[updatedAt] = at
            } > 0
        }

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
        if (updated == 0) null else readRow(id)
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
        if (updated == 0) null else readRow(id)
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
        if (updated == 0) null else readRow(id)
    }

    override suspend fun updateDefinition(
        watch: Watch,
        expectedVersion: Int,
        expectedStatus: WatchStatus
    ): Watch? =
        suspendTransaction(db) {
            val updated = WatchTable.update(where = {
                (WatchTable.id eq watch.id) and
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
            if (updated == 0) null else readRow(watch.id)
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
        if (updated == 0) null else readRow(id)
    }

    override suspend fun delete(id: String): Boolean = suspendTransaction(db) {
        WatchTable.deleteWhere { WatchTable.id eq id } > 0
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
