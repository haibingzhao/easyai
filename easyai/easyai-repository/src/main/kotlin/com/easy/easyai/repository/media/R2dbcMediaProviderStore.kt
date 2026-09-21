package com.easy.easyai.repository.media

import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.media.MediaProviderStore
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.map
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * R2DBC implementation of [MediaProviderStore] — one row per `(user_id, service_kind)`.
 *
 * Deliberately not `UserScope`-filtered beyond the exact owner: a credential row holds secrets, so
 * even the shared `system` bucket is only reachable through an explicit read of that owner, which
 * the resolver does with its own literal — never "the current request's user". Mirrors
 * [com.easy.easyai.repository.storage.R2dbcStorageSettingsStore].
 */
class R2dbcMediaProviderStore(private val db: R2dbcDatabase) : MediaProviderStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun get(userId: String, serviceKind: String): MediaProviderSettings? =
        suspendTransaction(db) {
            val table = Tables.MediaProviderSettingsTable
            table.selectAll()
                .where { (table.userId eq userId) and (table.serviceKind eq serviceKind) }
                .limit(1)
                .map { it.toSettings() }
                .firstOrNull()
        }

    override suspend fun list(userId: String): List<MediaProviderSettings> =
        suspendTransaction(db) {
            val table = Tables.MediaProviderSettingsTable
            table.selectAll()
                .where { table.userId eq userId }
                .map { it.toSettings() }
                .toList()
        }

    override suspend fun save(settings: MediaProviderSettings, userId: String) {
        val table = Tables.MediaProviderSettingsTable
        val now = System.currentTimeMillis()
        suspendTransaction(db) {
            val existing = table.selectAll()
                .where { (table.userId eq userId) and (table.serviceKind eq settings.serviceKind) }
                .firstOrNull()
            if (existing != null) {
                table.update(where = { table.id eq existing[table.id] }) {
                    it[enabled] = settings.enabled
                    it[providerType] = settings.providerType
                    it[baseUrl] = settings.baseUrl
                    it[region] = settings.region
                    it[apiKey] = settings.apiKey
                    it[accessKeyId] = settings.accessKeyId
                    it[accessKeySecret] = settings.accessKeySecret
                    it[defaultModel] = settings.defaultModel
                    it[options] = settings.options
                    it[timeoutSeconds] = settings.timeoutSeconds
                    it[updatedAt] = now
                }
                logger.debug("Updated media provider row for user '{}' kind '{}'", userId, settings.serviceKind)
            } else {
                table.insert {
                    it[id] = UUID.randomUUID().toString()
                    it[table.userId] = userId
                    it[this.serviceKind] = settings.serviceKind
                    it[enabled] = settings.enabled
                    it[providerType] = settings.providerType
                    it[baseUrl] = settings.baseUrl
                    it[region] = settings.region
                    it[this.apiKey] = settings.apiKey
                    it[this.accessKeyId] = settings.accessKeyId
                    it[this.accessKeySecret] = settings.accessKeySecret
                    it[defaultModel] = settings.defaultModel
                    it[options] = settings.options
                    it[timeoutSeconds] = settings.timeoutSeconds
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.debug("Inserted media provider row for user '{}' kind '{}'", userId, settings.serviceKind)
            }
        }
    }

    override suspend fun delete(userId: String, serviceKind: String): Boolean =
        suspendTransaction(db) {
            val table = Tables.MediaProviderSettingsTable
            val removed = table.deleteWhere {
                (table.userId eq userId) and (table.serviceKind eq serviceKind)
            }
            if (removed > 0) {
                logger.debug("Deleted media provider row for user '{}' kind '{}'", userId, serviceKind)
            }
            removed > 0
        }

    private fun ResultRow.toSettings(): MediaProviderSettings {
        val table = Tables.MediaProviderSettingsTable
        return MediaProviderSettings(
            enabled = this[table.enabled],
            serviceKind = this[table.serviceKind],
            providerType = this[table.providerType],
            baseUrl = this[table.baseUrl],
            region = this[table.region],
            apiKey = this[table.apiKey] ?: "",
            accessKeyId = this[table.accessKeyId],
            accessKeySecret = this[table.accessKeySecret] ?: "",
            defaultModel = this[table.defaultModel],
            options = this[table.options] ?: "",
            timeoutSeconds = this[table.timeoutSeconds]
        )
    }
}
