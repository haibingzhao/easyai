package com.easy.easyai.repository.task

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.repository.database.Tables.ScheduledTaskSettingTable
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.upsert
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper

/**
 * R2DBC implementation of [ScheduledTaskSettingStore] over the `scheduled_task_setting` table — one
 * row per `(user_id, task_type)`. Mirrors [com.easy.easyai.repository.model.R2dbcAuxModelSettingsStore]
 * for the composite key and [R2dbcAsyncTaskStore] for the JSON column handling.
 *
 * Ownership is strict per user ([UserScope.filterStrict]); `params` is opaque JSON owned by the
 * planner of that type, so this store never interprets it.
 */
class R2dbcScheduledTaskSettingStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : ScheduledTaskSettingStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun get(userId: String, taskType: AsyncTaskType): ScheduledTaskSetting? =
        suspendTransaction(db) {
            val table = ScheduledTaskSettingTable
            table.selectAll()
                .where {
                    UserScope.filterStrict(table.userId, userId) and (table.taskType eq taskType.key)
                }
                .limit(1)
                .map { it.toSetting() }
                .toList()
                .firstOrNull()
        }

    /**
     * Write the `(user, type)` row in one statement. A read-then-insert would let two concurrent saves
     * of the same row both miss and both insert, and the second then fails on the primary key — with
     * the transaction already aborted, there is no room to retry inside it.
     */
    override suspend fun save(setting: ScheduledTaskSetting): Unit = suspendTransaction(db) {
        val table = ScheduledTaskSettingTable
        val now = System.currentTimeMillis()
        val params = setting.params
            .takeIf { it.isNotEmpty() }
            ?.let { objectMapper.writeValueAsString(it) }
        table.upsert {
            it[table.userId] = setting.userId
            it[table.taskType] = setting.taskType.key
            it[table.enabled] = setting.enabled
            it[table.scheduleCron] = setting.cron
            it[table.params] = params
            it[table.timeoutMs] = setting.timeoutMs
            it[table.updatedAt] = now
        }
        logger.debug(
            "Saved scheduled settings for user '{}' type '{}' (enabled={})",
            setting.userId, setting.taskType.key, setting.enabled
        )
    }

    override suspend fun delete(userId: String, taskType: AsyncTaskType): Boolean = suspendTransaction(db) {
        val table = ScheduledTaskSettingTable
        table.deleteWhere {
            UserScope.filterStrict(table.userId, userId) and (table.taskType eq taskType.key)
        } > 0
    }

    override suspend fun listEnabled(taskType: AsyncTaskType?): List<ScheduledTaskSetting> =
        suspendTransaction(db) {
            val table = ScheduledTaskSettingTable
            var condition: Op<Boolean> = table.enabled eq true
            taskType?.let { condition = condition and (table.taskType eq it.key) }
            table.selectAll()
                .where { condition }
                .map { it.toSetting() }
                .toList()
        }

    /**
     * Map a row. The type key is opaque: a type with no planner registered in this build is skipped
     * by the runner's planner lookup, not here, so a rolled-back host does not lose the preference.
     */
    private fun ResultRow.toSetting(): ScheduledTaskSetting {
        val table = ScheduledTaskSettingTable
        val typeKey = this[table.taskType]
        val params = this[table.params]?.let { json ->
            runCatching { objectMapper.readValue(json, object : TypeReference<Map<String, Any?>>() {}) }
                .onFailure {
                    logger.warn(
                        "Unparsable params for user '{}' type '{}': {}",
                        this[table.userId], typeKey, it.message
                    )
                }
                .getOrNull()
        } ?: emptyMap()
        return ScheduledTaskSetting(
            userId = this[table.userId],
            taskType = AsyncTaskType(typeKey),
            enabled = this[table.enabled],
            cron = this[table.scheduleCron],
            params = params,
            timeoutMs = this[table.timeoutMs],
            updatedAt = this[table.updatedAt]
        )
    }
}
