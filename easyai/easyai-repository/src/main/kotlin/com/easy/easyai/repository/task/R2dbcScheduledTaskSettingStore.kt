package com.easy.easyai.repository.task

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingChanged
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.repository.database.Tables.ScheduledTaskSettingTable
import com.easy.easyai.repository.database.TransactionEvents
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.greater
import org.jetbrains.exposed.v1.core.isNull
import org.jetbrains.exposed.v1.core.lessEq
import org.jetbrains.exposed.v1.core.or
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import java.util.UUID

/** Shared per-user preferences and schedule state; only accepted task batches belong to a host. */
class R2dbcScheduledTaskSettingStore(
    private val db: R2dbcDatabase,
    private val host: HostIdentity,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance,
    private val onChanged: (ScheduledTaskSettingChanged) -> Unit = {}
) : ScheduledTaskSettingStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun get(userId: String, taskType: AsyncTaskType): ScheduledTaskSetting? =
        suspendTransaction(db) {
            val table = ScheduledTaskSettingTable
            table.selectAll()
                .where { key(userId, taskType) }
                .limit(1)
                .map { toSetting(it) }
                .toList()
                .firstOrNull()
        }

    override suspend fun save(setting: ScheduledTaskSetting): Unit = suspendTransaction(db) {
        val table = ScheduledTaskSettingTable
        // Serialize preference saves with initialization and firing; caller runtime state may be stale.
        val current = table.selectAll()
            .where { key(setting.userId, setting.taskType) }
            .forUpdate()
            .toList()
            .firstOrNull()
        val definitionChanged = current == null || current[table.enabled] != setting.enabled ||
            current[table.scheduleCron] != setting.cron
        val params = setting.params.takeIf { it.isNotEmpty() }?.let { objectMapper.writeValueAsString(it) }
        table.upsert {
            it[table.userId] = setting.userId
            it[table.taskType] = setting.taskType.key
            it[table.enabled] = setting.enabled
            it[table.scheduleCron] = setting.cron
            it[table.params] = params
            it[table.timeoutMs] = setting.timeoutMs
            it[table.updatedAt] = System.currentTimeMillis()
            it[table.scheduleRevision] = if (definitionChanged) UUID.randomUUID().toString() else current?.get(table.scheduleRevision)
            it[table.nextFireAt] = if (definitionChanged) null else current?.get(table.nextFireAt)
            it[table.scheduleZone] = if (definitionChanged) null else current?.get(table.scheduleZone)
        }
        if (definitionChanged) {
            TransactionEvents.afterCommit(this) { onChanged(ScheduledTaskSettingChanged(setting.userId, setting.taskType)) }
        }
        logger.debug(
            "Saved scheduled settings for user '{}' type '{}' (enabled={})",
            setting.userId, setting.taskType.key, setting.enabled
        )
    }

    override suspend fun delete(userId: String, taskType: AsyncTaskType): Boolean = suspendTransaction(db) {
        val deleted = ScheduledTaskSettingTable.deleteWhere { key(userId, taskType) } > 0
        if (deleted) TransactionEvents.afterCommit(this) { onChanged(ScheduledTaskSettingChanged(userId, taskType)) }
        deleted
    }

    override suspend fun listEnabled(taskType: AsyncTaskType?): List<ScheduledTaskSetting> =
        suspendTransaction(db) {
            val table = ScheduledTaskSettingTable
            var condition: Op<Boolean> = table.enabled eq true
            taskType?.let { condition = condition and (table.taskType eq it.key) }
            table.selectAll()
                .where { condition }
                .map { toSetting(it) }
                .toList()
        }

    override suspend fun listForScheduling(
        taskType: AsyncTaskType,
        horizon: Long,
        afterUserId: String?,
        limit: Int
    ): List<ScheduledTaskSetting> {
        require(limit > 0) { "Scheduling page size must be positive" }
        return suspendTransaction(db) {
            val table = ScheduledTaskSettingTable
            var condition = (table.enabled eq true) and (table.taskType eq taskType.key) and
                ((table.nextFireAt lessEq horizon) or table.scheduleZone.isNull())
            afterUserId?.let { condition = condition and (table.userId greater it) }
            table.selectAll().where { condition }
                .orderBy(table.userId to SortOrder.ASC)
                .limit(limit)
                .map { toSetting(it) }
                .toList()
        }
    }

    override suspend fun initializeSchedule(setting: ScheduledTaskSetting, nextFireAt: Long?, zone: String): Boolean {
        require(zone.isNotBlank()) { "Schedule zone must not be blank" }
        if (!setting.enabled || setting.scheduleZone != null) return false
        return suspendTransaction(db) {
            val table = ScheduledTaskSettingTable
            table.update({
                key(setting.userId, setting.taskType) and (table.enabled eq true) and
                    (table.scheduleRevision eq setting.scheduleRevision) and (table.scheduleCron eq setting.cron) and
                    table.scheduleZone.isNull()
            }) {
                it[table.nextFireAt] = nextFireAt
                it[table.scheduleZone] = zone
                it[table.scheduleRevision] = setting.scheduleRevision ?: UUID.randomUUID().toString()
            } > 0
        }
    }

    override suspend fun submitScheduled(
        setting: ScheduledTaskSetting,
        nextFireAt: Long?,
        tasks: List<AsyncTask>
    ): List<AsyncTask>? {
        tasks.forEach { task ->
            require(task.userId == setting.userId && task.type == setting.taskType) {
                "Scheduled tasks must match the setting user and type"
            }
            require(task.trigger == TaskTrigger.SCHEDULE && task.status == AsyncTaskStatus.PENDING) {
                "Scheduled tasks must be SCHEDULE-triggered and PENDING"
            }
        }
        if (!setting.enabled || setting.scheduleRevision == null || setting.scheduleZone == null || setting.nextFireAt == null) {
            return null
        }
        return suspendTransaction(db) {
            val table = ScheduledTaskSettingTable
            val advanced = table.update({
                key(setting.userId, setting.taskType) and (table.enabled eq true) and
                    (table.scheduleRevision eq setting.scheduleRevision) and (table.scheduleZone eq setting.scheduleZone) and
                    (table.nextFireAt eq setting.nextFireAt)
            }) {
                it[table.nextFireAt] = nextFireAt
            } > 0
            if (!advanced) return@suspendTransaction null
            // No nested create(): advancing and every insert must share this transaction.
            tasks.map { R2dbcAsyncTaskStore.insertRow(it, host.appName, objectMapper) }
        }
    }

    private fun key(userId: String, taskType: AsyncTaskType): Op<Boolean> {
        val table = ScheduledTaskSettingTable
        return UserScope.filterStrict(table.userId, userId) and (table.taskType eq taskType.key)
    }

    private fun toSetting(row: ResultRow): ScheduledTaskSetting {
        val table = ScheduledTaskSettingTable
        val typeKey = row[table.taskType]
        val params = row[table.params]?.let { json ->
            runCatching { objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {}) }
                .onFailure {
                    logger.warn("Unparsable params for user '{}' type '{}': {}", row[table.userId], typeKey, it.message)
                }
                .getOrNull()
        } ?: emptyMap()
        return ScheduledTaskSetting(
            userId = row[table.userId],
            taskType = AsyncTaskType(typeKey),
            enabled = row[table.enabled],
            cron = row[table.scheduleCron],
            params = params,
            timeoutMs = row[table.timeoutMs],
            updatedAt = row[table.updatedAt],
            nextFireAt = row[table.nextFireAt],
            scheduleRevision = row[table.scheduleRevision],
            scheduleZone = row[table.scheduleZone]
        )
    }
}
