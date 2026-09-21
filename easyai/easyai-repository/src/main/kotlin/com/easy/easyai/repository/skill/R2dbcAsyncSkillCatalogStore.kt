package com.easy.easyai.repository.skill

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.repository.database.Tables
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
import org.slf4j.LoggerFactory
import java.sql.SQLException
import java.util.UUID

/**
 * R2DBC implementation of [AsyncSkillCatalogStore].
 *
 * Every lookup and mutation is filtered on the **strict** owner: a skill row is private to
 * its `user_id`, so nothing here widens the predicate to the shared `system` bucket. Whether
 * a caller may fall back to the system-owned catalog is decided one layer up, where that
 * fallback is explicit and auditable.
 */
class R2dbcAsyncSkillCatalogStore(private val db: R2dbcDatabase) : AsyncSkillCatalogStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun upsert(entry: SkillCatalogEntry): SkillCatalogEntry {
        val table = Tables.SkillTable
        val now = System.currentTimeMillis()
        return suspendTransaction(db) {
            val existing = table.selectAll()
                .where {
                    (table.userId eq entry.userId) and
                        (table.name eq entry.name) and
                        (table.projectHash eq entry.projectHash)
                }
                .firstOrNull()

            if (existing != null) {
                updateRow(existing[table.id], entry, now)
                logger.debug("Updated skill catalog row: {}/{} (source={})", entry.userId, entry.name, entry.source)
                toCatalogEntry(existing).copy(
                    source = entry.source,
                    version = entry.version,
                    checksum = entry.checksum,
                    enabled = entry.enabled,
                    installPath = entry.installPath,
                    origin = entry.origin,
                    updatedAt = now
                )
            } else {
                // SELECT-then-INSERT is not atomic: a concurrent `backfillAll` on the same key can
                // trip the (user_id, name, project_hash) unique index. Retry once as an update
                // instead of letting the whole claim pass swallow a legitimate row.
                val id = entry.id.ifBlank { UUID.randomUUID().toString() }
                val inserted = try {
                    table.insert {
                        it[table.id] = id
                        it[name] = entry.name
                        it[skillSource] = entry.source
                        it[version] = entry.version
                        it[checksum] = entry.checksum
                        it[enabled] = entry.enabled
                        it[installPath] = entry.installPath
                        it[origin] = entry.origin
                        it[table.userId] = entry.userId
                        it[projectHash] = entry.projectHash
                        it[createdAt] = now
                        it[updatedAt] = now
                    }
                    true
                } catch (e: Exception) {
                    if (!isUniqueViolation(e)) throw e
                    false
                }
                if (inserted) {
                    logger.debug("Inserted skill catalog row: {}/{} (source={})", entry.userId, entry.name, entry.source)
                    entry.copy(id = id, createdAt = now, updatedAt = now)
                } else {
                    val winner = table.selectAll()
                        .where {
                            (table.userId eq entry.userId) and
                                (table.name eq entry.name) and
                                (table.projectHash eq entry.projectHash)
                        }
                        .firstOrNull()
                        ?: throw IllegalStateException(
                            "Skill catalog upsert lost a race for (${entry.userId}, ${entry.name}, ${entry.projectHash}) " +
                                "but the winning row is not observable"
                        )
                    updateRow(winner[table.id], entry, now)
                    logger.debug(
                        "Skill catalog row won by a concurrent writer, updated instead: {}/{}",
                        entry.userId, entry.name
                    )
                    toCatalogEntry(winner).copy(
                        source = entry.source,
                        version = entry.version,
                        checksum = entry.checksum,
                        enabled = entry.enabled,
                        installPath = entry.installPath,
                        origin = entry.origin,
                        updatedAt = now
                    )
                }
            }
        }
    }

    private suspend fun updateRow(id: String, entry: SkillCatalogEntry, now: Long) {
        val table = Tables.SkillTable
        table.update(where = { table.id eq id }) {
            it[skillSource] = entry.source
            it[version] = entry.version
            it[checksum] = entry.checksum
            it[enabled] = entry.enabled
            it[installPath] = entry.installPath
            it[origin] = entry.origin
            it[updatedAt] = now
        }
    }

    override suspend fun listByName(name: String, userId: String): List<SkillCatalogEntry> =
        suspendTransaction(db) {
            val table = Tables.SkillTable
            table.selectAll()
                .where { (table.userId eq userId) and (table.name eq name) }
                .map { toCatalogEntry(it) }
                .toList()
        }

    override suspend fun listByUser(userId: String): List<SkillCatalogEntry> =
        suspendTransaction(db) {
            val table = Tables.SkillTable
            table.selectAll()
                .where { table.userId eq userId }
                .orderBy(table.name to SortOrder.ASC)
                .map { toCatalogEntry(it) }
                .toList()
        }

    override suspend fun listAll(): List<SkillCatalogEntry> =
        suspendTransaction(db) {
            val table = Tables.SkillTable
            table.selectAll()
                .orderBy(table.userId to SortOrder.ASC, table.name to SortOrder.ASC)
                .map { toCatalogEntry(it) }
                .toList()
        }

    override suspend fun listDistinctUserIds(): List<String> =
        suspendTransaction(db) {
            val table = Tables.SkillTable
            // Push DISTINCT down to SQL instead of materialising the whole table: on a large
            // catalog the in-memory variant allocates a row per skill just to keep a set of users.
            table.select(table.userId)
                .withDistinct()
                .orderBy(table.userId to SortOrder.ASC)
                .map { it[table.userId] }
                .toList()
        }

    override suspend fun setEnabled(id: String, enabled: Boolean): Boolean =
        suspendTransaction(db) {
            val table = Tables.SkillTable
            val existing = table.selectAll().where { table.id eq id }.firstOrNull()
            if (existing == null) {
                logger.debug("Skill enablement skipped: no row with id {}", id)
                return@suspendTransaction false
            }
            table.update(where = { table.id eq id }) {
                it[table.enabled] = enabled
                it[table.updatedAt] = System.currentTimeMillis()
            }
            true
        }

    override suspend fun updateChecksum(id: String, checksum: String, version: String): Boolean =
        suspendTransaction(db) {
            val table = Tables.SkillTable
            val existing = table.selectAll().where { table.id eq id }.firstOrNull()
            if (existing == null) {
                logger.debug("Skill checksum update skipped: no row with id {}", id)
                return@suspendTransaction false
            }
            table.update(where = { table.id eq id }) {
                it[table.checksum] = checksum
                it[table.version] = version
                it[table.updatedAt] = System.currentTimeMillis()
            }
            true
        }

    override suspend fun delete(id: String): Boolean =
        suspendTransaction(db) {
            val table = Tables.SkillTable
            val existing = table.selectAll().where { table.id eq id }.firstOrNull()
            if (existing == null) {
                logger.debug("Skill catalog delete skipped: no row with id {}", id)
                return@suspendTransaction false
            }
            table.deleteWhere { table.id eq id }
            logger.debug("Deleted skill catalog row: {}", id)
            true
        }

    companion object {
        /**
         * Detect a UNIQUE-constraint violation across the two dialects this project ships with
         * (H2 in MODE=MYSQL and PostgreSQL). Both surface SQLState 23xxx; drivers that do not
         * expose SQLState fall back to a message scan so the retry path still works.
         */
        private fun isUniqueViolation(error: Throwable): Boolean {
            var cur: Throwable? = error
            while (cur != null) {
                if (cur is SQLException) {
                    val state = cur.sqlState
                    if (state != null && state.startsWith("23")) return true
                }
                val cls = cur.javaClass.name
                if (cls.contains("IntegrityConstraintViolation") || cls.contains("DuplicateKey")) return true
                val msg = cur.message?.lowercase()
                if (msg != null && (msg.contains("unique index") || msg.contains("unique constraint") ||
                        msg.contains("duplicate key") || msg.contains("duplicate entry"))
                ) return true
                cur = cur.cause
            }
            return false
        }

        /** Named to avoid clashing with the local `entry` parameter used inside upsert. */
        private fun toCatalogEntry(row: ResultRow): SkillCatalogEntry {
            val table = Tables.SkillTable
            return SkillCatalogEntry(
                id = row[table.id],
                name = row[table.name],
                source = row[table.skillSource],
                version = row[table.version],
                checksum = row[table.checksum],
                enabled = row[table.enabled],
                installPath = row[table.installPath],
                origin = row[table.origin],
                userId = row[table.userId],
                projectHash = row[table.projectHash],
                createdAt = row[table.createdAt],
                updatedAt = row[table.updatedAt]
            )
        }
    }
}
