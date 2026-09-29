package com.easy.easyai.repository.model

import com.easy.easyai.core.model.aux.AuxModelSettings
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * R2DBC implementation of [AuxModelSettingsStore] — one row per `(user, task)`, upserted by that
 * composite key. Mirrors [com.easy.easyai.repository.storage.R2dbcStorageSettingsStore].
 */
class R2dbcAuxModelSettingsStore(private val db: R2dbcDatabase) : AuxModelSettingsStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun get(userId: String, task: AuxModelTask): AuxModelSettings? {
        val table = Tables.AuxModelSettingsTable
        return suspendTransaction(db) {
            table.selectAll()
                .where { (table.userId eq userId) and (table.taskKey eq task.key) }
                .limit(1)
                .map { it.toSettings() }
                .firstOrNull()
        }
    }

    override suspend fun getAll(userId: String): List<AuxModelSettings> {
        val table = Tables.AuxModelSettingsTable
        return suspendTransaction(db) {
            table.selectAll()
                .where { table.userId eq userId }
                .map { it.toSettings() }
                .toList()
        }
    }

    override suspend fun save(userId: String, task: AuxModelTask, modelConfigId: String) {
        val table = Tables.AuxModelSettingsTable
        val now = System.currentTimeMillis()
        suspendTransaction(db) {
            val existing = table.selectAll()
                .where { (table.userId eq userId) and (table.taskKey eq task.key) }
                .firstOrNull()
            if (existing != null) {
                table.update(where = { table.id eq existing[table.id] }) {
                    it[table.modelConfigId] = modelConfigId
                    it[updatedAt] = now
                }
                logger.debug("Updated aux model row for user '{}' task '{}'", userId, task.key)
            } else {
                table.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[table.userId] = userId
                    it[taskKey] = task.key
                    it[table.modelConfigId] = modelConfigId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.debug("Inserted aux model row for user '{}' task '{}'", userId, task.key)
            }
        }
    }

    override suspend fun delete(userId: String, task: AuxModelTask): Boolean {
        val table = Tables.AuxModelSettingsTable
        return suspendTransaction(db) {
            val removed = table.deleteWhere { (table.userId eq userId) and (table.taskKey eq task.key) }
            if (removed > 0) {
                logger.debug("Deleted aux model row for user '{}' task '{}'", userId, task.key)
            }
            removed > 0
        }
    }

    private fun ResultRow.toSettings(): AuxModelSettings {
        val table = Tables.AuxModelSettingsTable
        return AuxModelSettings(
            taskKey = this[table.taskKey],
            modelConfigId = this[table.modelConfigId]
        )
    }
}
