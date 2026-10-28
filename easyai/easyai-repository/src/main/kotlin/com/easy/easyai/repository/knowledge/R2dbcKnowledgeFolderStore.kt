package com.easy.easyai.repository.knowledge

import com.easy.easyai.core.knowledge.KnowledgeFolder
import com.easy.easyai.core.knowledge.KnowledgeFolderStore
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory

class R2dbcKnowledgeFolderStore(private val db: R2dbcDatabase) : KnowledgeFolderStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun listByBuckets(userIds: Collection<String>): List<KnowledgeFolder> {
        if (userIds.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            val table = Tables.KnowledgeFolderTable
            table.selectAll()
                .where { table.userId inList userIds.toList() }
                .toList()
                .map { it.toFolder() }
        }
    }

    override suspend fun findById(id: String): KnowledgeFolder? =
        suspendTransaction(db) {
            val table = Tables.KnowledgeFolderTable
            table.selectAll()
                .where { table.id eq id }
                .limit(1)
                .toList()
                .firstOrNull()
                ?.toFolder()
        }

    override suspend fun findDefault(userId: String): KnowledgeFolder? =
        suspendTransaction(db) {
            val table = Tables.KnowledgeFolderTable
            table.selectAll()
                .where {
                    (table.userId eq userId) and (table.parentId eq "") and (table.isDefault eq true)
                }
                .limit(1)
                .toList()
                .firstOrNull()
                ?.toFolder()
        }

    override suspend fun save(folder: KnowledgeFolder) {
        val table = Tables.KnowledgeFolderTable
        val parentKey = folder.parentId ?: ""
        suspendTransaction(db) {
            val existing = table.selectAll()
                .where { table.id eq folder.id }
                .limit(1)
                .toList()
                .firstOrNull()

            if (existing != null) {
                table.update(where = { table.id eq folder.id }) {
                    it[userId] = folder.userId
                    it[parentId] = parentKey
                    it[name] = folder.name
                    it[isDefault] = folder.isDefault
                    it[createdBy] = folder.createdBy
                    it[updatedAt] = folder.updatedAt
                }
                logger.debug("Updated knowledge folder: {}", folder.id)
            } else {
                table.insert {
                    it[id] = folder.id
                    it[userId] = folder.userId
                    it[parentId] = parentKey
                    it[name] = folder.name
                    it[isDefault] = folder.isDefault
                    it[createdBy] = folder.createdBy
                    it[createdAt] = folder.createdAt
                    it[updatedAt] = folder.updatedAt
                }
                logger.debug("Inserted knowledge folder: {}", folder.id)
            }
        }
    }

    override suspend fun delete(id: String) {
        suspendTransaction(db) {
            val table = Tables.KnowledgeFolderTable
            val deleted = table.deleteWhere { table.id eq id }
            if (deleted > 0) {
                logger.debug("Deleted knowledge folder: {}", id)
            }
        }
    }

    override suspend fun deleteIfEmpty(id: String, subtreeIds: Collection<String>): Boolean =
        suspendTransaction(db) {
            val folders = Tables.KnowledgeFolderTable
            val articles = Tables.KnowledgeArticleTable
            if (folders.selectAll().where { folders.parentId eq id }.count() > 0) {
                return@suspendTransaction false
            }
            if (subtreeIds.isNotEmpty() &&
                articles.selectAll().where { articles.folderId inList subtreeIds.toList() }.count() > 0
            ) {
                return@suspendTransaction false
            }
            folders.deleteWhere { folders.id eq id } > 0
        }

    private fun ResultRow.toFolder(): KnowledgeFolder {
        val table = Tables.KnowledgeFolderTable
        return KnowledgeFolder(
            id = this[table.id],
            userId = this[table.userId],
            parentId = this[table.parentId].ifBlank { null },
            name = this[table.name],
            isDefault = this[table.isDefault],
            createdBy = this[table.createdBy],
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt]
        )
    }
}
