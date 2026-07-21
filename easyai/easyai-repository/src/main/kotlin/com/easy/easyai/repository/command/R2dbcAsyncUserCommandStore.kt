package com.easy.easyai.repository.command

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.core.command.UserCommandDefinition
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.*
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.*

/**
 * R2DBC-based implementation of AsyncUserCommandStore.
 * Stores aliases and hints as JSON arrays in text columns.
 */
class R2dbcAsyncUserCommandStore(private val db: R2dbcDatabase) : AsyncUserCommandStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun save(command: UserCommandDefinition, userId: String): UserCommandDefinition {
        val table = Tables.UserCommandTable
        val now = Instant.now().epochSecond
        val id = command.id.ifBlank { UUID.randomUUID().toString() }

        suspendTransaction(db) {
            // Check if a command with same userId+name already exists
            val existing = table.selectAll()
                .where {
                    (table.userId eq userId) and (table.name eq command.name)
                }
                .firstOrNull()

            if (existing != null) {
                table.update(
                    where = { (table.id eq existing[table.id]) and UserScope.filter(table.userId, userId) }
                ) {
                    it[description] = command.description
                    it[aliases] = toJsonArray(command.aliases)
                    it[template] = command.template
                    it[hints] = toJsonArray(command.hints)
                    it[updatedAt] = now
                }
                logger.info("Updated user command: {} (userId={})", command.name, userId)
            } else {
                table.insert {
                    it[table.id] = id
                    it[name] = command.name
                    it[description] = command.description
                    it[aliases] = toJsonArray(command.aliases)
                    it[template] = command.template
                    it[hints] = toJsonArray(command.hints)
                    it[table.userId] = userId
                    it[createdAt] = now
                    it[updatedAt] = now
                }
                logger.info("Inserted user command: {} (userId={})", command.name, userId)
            }
        }

        return command.copy(id = id, userId = userId, updatedAt = now,
            createdAt = command.createdAt.takeIf { it > 0 } ?: now)
    }

    override suspend fun findById(id: String, userId: String): UserCommandDefinition? {
        return suspendTransaction(db) {
            val table = Tables.UserCommandTable
            table.selectAll()
                .where { (table.id eq id) and UserScope.filter(table.userId, userId) }
                .limit(1)
                .map { it.toCommand() }
                .firstOrNull()
        }
    }

    override suspend fun findByName(name: String, userId: String): UserCommandDefinition? {
        return suspendTransaction(db) {
            val table = Tables.UserCommandTable
            table.selectAll()
                .where { (table.name eq name) and UserScope.filter(table.userId, userId) }
                .limit(1)
                .map { it.toCommand() }
                .firstOrNull()
        }
    }

    override suspend fun findAll(userId: String): List<UserCommandDefinition> {
        return suspendTransaction(db) {
            val table = Tables.UserCommandTable
            table.selectAll()
                .where { UserScope.filter(table.userId, userId) }
                .map { it.toCommand() }
                .toList()
                .sortedBy { it.name }
        }
    }

    override suspend fun update(command: UserCommandDefinition, userId: String): UserCommandDefinition {
        return save(command, userId)
    }

    override suspend fun delete(id: String, userId: String) {
        suspendTransaction(db) {
            val table = Tables.UserCommandTable
            val ownedCount = table.selectAll()
                .where { (table.id eq id) and UserScope.filterStrict(table.userId, userId) }
                .count()
            if (ownedCount > 0) {
                table.deleteWhere { table.id eq id }
                logger.info("Deleted user command: {} (userId={})", id, userId)
            } else {
                logger.debug("Command {} not owned by user {}, skipping delete", id, userId)
            }
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toCommand(): UserCommandDefinition {
        val table = Tables.UserCommandTable
        return UserCommandDefinition(
            id = this[table.id],
            name = this[table.name],
            description = this[table.description],
            aliases = parseJsonArray(this[table.aliases]),
            template = this[table.template] ?: "",
            hints = parseJsonArray(this[table.hints]),
            userId = this[table.userId],
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt],
        )
    }

    companion object {
        private val jsonMapper = SharedObjectMapper.instance

        private fun toJsonArray(list: List<String>): String? {
            if (list.isEmpty()) return null
            return jsonMapper.writeValueAsString(list)
        }

        private fun parseJsonArray(json: String?): List<String> {
            if (json.isNullOrBlank()) return emptyList()
            return try {
                val node = jsonMapper.readTree(json)
                if (node.isArray) {
                    val result = mutableListOf<String>()
                    for (el in node) { result.add(el.asString()) }
                    result
                } else {
                    emptyList()
                }
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
