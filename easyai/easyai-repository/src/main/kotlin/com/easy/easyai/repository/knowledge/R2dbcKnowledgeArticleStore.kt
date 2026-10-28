package com.easy.easyai.repository.knowledge

import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
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

class R2dbcKnowledgeArticleStore(private val db: R2dbcDatabase) : KnowledgeArticleStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun save(article: KnowledgeArticle) {
        val table = Tables.KnowledgeArticleTable
        suspendTransaction(db) {
            val existing = table.selectAll()
                .where { table.id eq article.id }
                .limit(1)
                .toList()
                .firstOrNull()

            if (existing != null) {
                table.update(where = { table.id eq article.id }) {
                    it[userId] = article.userId
                    it[createdBy] = article.createdBy
                    it[folderId] = article.folderId
                    it[fileName] = article.fileName
                    it[title] = article.title
                    it[description] = article.description
                    it[category] = article.category
                    it[ext] = article.ext
                    it[origin] = article.origin
                    it[originSessionId] = article.originSessionId
                    it[originMessageId] = article.originMessageId
                    it[composedFrom] = article.composedFrom
                    it[status] = ArticleStatus.toWire(article.status)
                    it[ragEnabled] = article.ragEnabled
                    it[updatedAt] = article.updatedAt
                }
                logger.debug("Updated knowledge article: {}", article.id)
            } else {
                table.insert {
                    it[id] = article.id
                    it[userId] = article.userId
                    it[createdBy] = article.createdBy
                    it[folderId] = article.folderId
                    it[fileName] = article.fileName
                    it[title] = article.title
                    it[description] = article.description
                    it[category] = article.category
                    it[ext] = article.ext
                    it[origin] = article.origin
                    it[originSessionId] = article.originSessionId
                    it[originMessageId] = article.originMessageId
                    it[composedFrom] = article.composedFrom
                    it[status] = ArticleStatus.toWire(article.status)
                    it[ragEnabled] = article.ragEnabled
                    it[createdAt] = article.createdAt
                    it[updatedAt] = article.updatedAt
                }
                logger.debug("Inserted knowledge article: {}", article.id)
            }
        }
    }

    override suspend fun findById(id: String): KnowledgeArticle? =
        suspendTransaction(db) {
            val table = Tables.KnowledgeArticleTable
            table.selectAll()
                .where { table.id eq id }
                .limit(1)
                .toList()
                .firstOrNull()
                ?.toArticle()
        }

    override suspend fun findByIds(userId: String, ids: Collection<String>): List<KnowledgeArticle> {
        if (ids.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            val table = Tables.KnowledgeArticleTable
            table.selectAll()
                .where {
                    (table.userId eq userId) and (table.id inList ids.toList())
                }
                .toList()
                .map { it.toArticle() }
        }
    }

    override suspend fun findByFolderAndFileName(
        userId: String,
        folderId: String,
        fileName: String,
    ): KnowledgeArticle? = suspendTransaction(db) {
        val table = Tables.KnowledgeArticleTable
        table.selectAll()
            .where {
                (table.userId eq userId) and (table.folderId eq folderId) and (table.fileName eq fileName)
            }
            .limit(1)
            .toList()
            .firstOrNull()
            ?.toArticle()
    }

    override fun findAllInBuckets(
        userIds: Collection<String>,
        folderId: String?,
        category: String?,
        status: ArticleStatus?,
    ): Flow<KnowledgeArticle> = flow {
        if (userIds.isEmpty()) return@flow
        val table = Tables.KnowledgeArticleTable
        val rows = suspendTransaction(db) {
            var condition: Op<Boolean> = table.userId inList userIds.toList()
            if (!folderId.isNullOrBlank()) {
                condition = condition and (table.folderId eq folderId)
            }
            if (!category.isNullOrBlank()) {
                condition = condition and (table.category eq category)
            }
            if (status != null) {
                condition = condition and (table.status eq ArticleStatus.toWire(status))
            }
            table.selectAll()
                .where { condition }
                // Id breaks updated_at ties: the article tool pages with offset/limit, and an
                // undefined order for rows written in the same millisecond can repeat or skip one.
                .orderBy(table.updatedAt to SortOrder.DESC, table.id to SortOrder.ASC)
                .toList()
        }
        for (row in rows) {
            emit(row.toArticle())
        }
    }

    override suspend fun delete(id: String) {
        suspendTransaction(db) {
            val table = Tables.KnowledgeArticleTable
            val deleted = table.deleteWhere { table.id eq id }
            if (deleted > 0) {
                logger.debug("Deleted knowledge article: {}", id)
            }
        }
    }

    override suspend fun deleteByUser(userId: String) {
        suspendTransaction(db) {
            val table = Tables.KnowledgeArticleTable
            val deleted = table.deleteWhere { table.userId eq userId }
            logger.debug("Deleted {} knowledge articles for user '{}'", deleted, userId)
        }
    }

    override suspend fun deleteByCreatedBy(userId: String, createdBy: String) {
        suspendTransaction(db) {
            val table = Tables.KnowledgeArticleTable
            val deleted = table.deleteWhere {
                (table.userId eq userId) and (table.createdBy eq createdBy)
            }
            logger.debug("Deleted {} knowledge articles created by '{}' in bucket '{}'", deleted, createdBy, userId)
        }
    }

    private fun ResultRow.toArticle(): KnowledgeArticle {
        val table = Tables.KnowledgeArticleTable
        return KnowledgeArticle(
            id = this[table.id],
            userId = this[table.userId],
            createdBy = this[table.createdBy],
            folderId = this[table.folderId],
            fileName = this[table.fileName],
            title = this[table.title],
            description = this[table.description],
            category = this[table.category],
            ext = this[table.ext],
            origin = this[table.origin],
            originSessionId = this[table.originSessionId],
            originMessageId = this[table.originMessageId],
            composedFrom = this[table.composedFrom],
            status = ArticleStatus.fromColumn(this[table.status]),
            ragEnabled = this[table.ragEnabled],
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt]
        )
    }
}
