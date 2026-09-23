package com.easy.easyai.repository.skill

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.skill.SkillSyncUpdate
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import java.util.UUID

class R2dbcAsyncSkillCatalogStore(private val db: R2dbcDatabase) : AsyncSkillCatalogStore {
    private val table = Tables.SkillTable

    override suspend fun claim(entry: SkillCatalogEntry): SkillCatalogEntry {
        // Catch outside suspendTransaction: PostgreSQL cannot query a transaction aborted by 23505.
        repeat(MAX_RETRIES) {
            try {
                return suspendTransaction(db) {
                    val existing = table.selectAll().where {
                        (table.userId eq entry.userId) and (table.name eq entry.name) and
                            (table.projectHash eq entry.projectHash)
                    }.firstOrNull()
                    if (existing != null) return@suspendTransaction toEntry(existing)
                    val now = System.currentTimeMillis()
                    val row = entry.copy(
                        id = entry.id.ifBlank { UUID.randomUUID().toString() },
                        createdAt = now, updatedAt = now, indexedChecksum = null, revision = 0,
                        syncState = if (entry.enabled) SkillSyncState.PENDING_INDEX else SkillSyncState.PENDING_DELETE
                    )
                    table.insert {
                        it[id] = row.id
                        it[name] = row.name
                        it[skillSource] = row.source
                        it[version] = row.version
                        it[checksum] = row.checksum
                        it[enabled] = row.enabled
                        it[installPath] = row.installPath
                        it[origin] = row.origin
                        it[userId] = row.userId
                        it[projectHash] = row.projectHash
                        it[createdAt] = now
                        it[updatedAt] = now
                        it[syncState] = row.syncState.name
                        it[revision] = row.revision
                        it[indexProjectPath] = row.indexProjectPath
                    }
                    row
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!isUniqueViolation(e)) throw e
            }
        }
        error("Concurrent skill claim did not converge")
    }

    override suspend fun findById(id: String): SkillCatalogEntry? = suspendTransaction(db) {
        table.selectAll().where { table.id eq id }.firstOrNull()?.let { toEntry(it) }
    }

    override suspend fun listByName(name: String, userId: String): List<SkillCatalogEntry> = suspendTransaction(db) {
        table.selectAll().where { (table.userId eq userId) and (table.name eq name) }.map { toEntry(it) }.toList()
    }

    override suspend fun listByUser(userId: String): List<SkillCatalogEntry> = suspendTransaction(db) {
        table.selectAll().where { table.userId eq userId }.orderBy(table.name to SortOrder.ASC)
            .map { toEntry(it) }.toList()
    }

    override suspend fun listAll(): List<SkillCatalogEntry> = suspendTransaction(db) {
        table.selectAll().orderBy(table.userId to SortOrder.ASC, table.name to SortOrder.ASC)
            .map { toEntry(it) }.toList()
    }

    override suspend fun listDistinctUserIds(): List<String> = suspendTransaction(db) {
        table.select(table.userId).withDistinct().map { it[table.userId] }.toList()
    }

    override suspend fun setEnabled(id: String, enabled: Boolean): Boolean {
        repeat(MAX_RETRIES) {
            val row = findById(id) ?: return false
            val changed = suspendTransaction(db) {
                table.update({ (table.id eq id) and (table.revision eq row.revision) }) {
                    it[table.enabled] = enabled
                    it[syncState] = if (enabled) SkillSyncState.PENDING_INDEX.name else SkillSyncState.PENDING_DELETE.name
                    it[revision] = row.revision + 1
                    it[nextAttemptAt] = null
                    it[lastError] = null
                    it[updatedAt] = System.currentTimeMillis()
                } > 0
            }
            if (changed) return true
        }
        return false
    }

    override suspend fun updateContent(
        id: String, expectedRevision: Long, checksum: String, version: String, enable: Boolean
    ): Boolean = suspendTransaction(db) {
        val row = table.selectAll().where { table.id eq id }.firstOrNull()?.let { toEntry(it) }
            ?: return@suspendTransaction false
        if (row.revision != expectedRevision) return@suspendTransaction false
        table.update({ (table.id eq id) and (table.revision eq expectedRevision) }) {
            it[table.checksum] = checksum
            it[table.version] = version
            if (enable) it[enabled] = true
            it[syncState] = if (enable || row.enabled) SkillSyncState.PENDING_INDEX.name else SkillSyncState.PENDING_DELETE.name
            it[revision] = expectedRevision + 1
            it[nextAttemptAt] = null
            it[lastError] = null
            it[updatedAt] = System.currentTimeMillis()
        } > 0
    }

    override suspend fun updateSync(id: String, expectedRevision: Long, update: SkillSyncUpdate): Boolean =
        suspendTransaction(db) {
            val row = table.selectAll().where { table.id eq id }.firstOrNull()?.let { toEntry(it) }
                ?: return@suspendTransaction false
            if (row.revision != expectedRevision) return@suspendTransaction false
            if (update.state == SkillSyncState.SYNCED && (!row.enabled || update.indexedChecksum != row.checksum)) {
                return@suspendTransaction false
            }
            if (!row.enabled && update.state in setOf(SkillSyncState.SUBMITTED, SkillSyncState.PENDING_INDEX)) {
                return@suspendTransaction false
            }
            table.update({ (table.id eq id) and (table.revision eq expectedRevision) }) {
                it[syncState] = update.state.name
                it[indexedChecksum] = update.indexedChecksum
                it[nextAttemptAt] = update.nextAttemptAt
                it[lastError] = update.lastError?.take(2000)
                it[revision] = expectedRevision + 1
                it[updatedAt] = System.currentTimeMillis()
            } > 0
        }

    override suspend fun delete(id: String): Boolean = suspendTransaction(db) {
        table.deleteWhere { table.id eq id } > 0
    }

    private fun toEntry(row: ResultRow) = SkillCatalogEntry(
        id = row[table.id], name = row[table.name], source = row[table.skillSource],
        version = row[table.version], checksum = row[table.checksum], enabled = row[table.enabled],
        installPath = row[table.installPath], origin = row[table.origin], userId = row[table.userId],
        projectHash = row[table.projectHash], createdAt = row[table.createdAt], updatedAt = row[table.updatedAt],
        indexedChecksum = row[table.indexedChecksum], syncState = SkillSyncState.valueOf(row[table.syncState]),
        revision = row[table.revision], nextAttemptAt = row[table.nextAttemptAt], lastError = row[table.lastError],
        indexProjectPath = row[table.indexProjectPath]
    )

    private fun isUniqueViolation(error: Throwable): Boolean {
        var current: Throwable? = error
        while (current != null) {
            val message = current.message.orEmpty().lowercase()
            if (message.contains("23505") || message.contains("unique index") ||
                message.contains("unique constraint") || message.contains("duplicate key")) return true
            current = current.cause
        }
        return false
    }

    private companion object { const val MAX_RETRIES = 8 }
}
