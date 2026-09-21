package com.easy.easyai.repository.storage

import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.core.storage.StorageSettingsStore
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * R2DBC implementation of [StorageSettingsStore] — one row per owner, upserted by `user_id`.
 *
 * Deliberately not `UserScope`-filtered beyond the exact owner: a storage row holds credentials,
 * so even the shared `system` bucket is only reachable through an explicit read of that owner,
 * which the resolver does with its own literal — never "the current request's user".
 */
class R2dbcStorageSettingsStore(private val db: R2dbcDatabase) : StorageSettingsStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun get(userId: String): StorageSettings? =
        suspendTransaction(db) {
            val table = Tables.StorageSettingsTable
            table.selectAll()
                .where { table.userId eq userId }
                .limit(1)
                .map { it.toSettings() }
                .firstOrNull()
        }

    override suspend fun save(settings: StorageSettings, userId: String) {
        val table = Tables.StorageSettingsTable
        val now = System.currentTimeMillis()
        suspendTransaction(db) {
            val existing = table.selectAll().where { table.userId eq userId }.firstOrNull()
            if (existing != null) {
                table.update(where = { table.id eq existing[table.id] }) {
                    it[enabled] = settings.enabled
                    it[storageType] = settings.type
                    it[endpoint] = settings.endpoint
                    it[bucket] = settings.bucket
                    it[accessKeyId] = settings.accessKeyId
                    it[accessKeySecret] = settings.accessKeySecret
                    it[localDir] = settings.localDir
                    it[updatedAt] = now
                }
                logger.debug("Updated storage settings row for user '{}'", userId)
            } else {
                table.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[table.userId] = userId
                    it[enabled] = settings.enabled
                    it[storageType] = settings.type
                    it[endpoint] = settings.endpoint
                    it[bucket] = settings.bucket
                    it[accessKeyId] = settings.accessKeyId
                    it[accessKeySecret] = settings.accessKeySecret
                    it[localDir] = settings.localDir
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.debug("Inserted storage settings row for user '{}'", userId)
            }
        }
    }

    override suspend fun delete(userId: String): Boolean =
        suspendTransaction(db) {
            val table = Tables.StorageSettingsTable
            val removed = table.deleteWhere { table.userId eq userId }
            if (removed > 0) {
                logger.debug("Deleted storage settings row for user '{}'", userId)
            }
            removed > 0
        }

    private fun ResultRow.toSettings(): StorageSettings {
        val table = Tables.StorageSettingsTable
        return StorageSettings(
            enabled = this[table.enabled],
            type = this[table.storageType],
            endpoint = this[table.endpoint],
            bucket = this[table.bucket],
            accessKeyId = this[table.accessKeyId],
            accessKeySecret = this[table.accessKeySecret],
            localDir = this[table.localDir]
        )
    }
}
