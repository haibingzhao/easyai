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
                    it[knowledgeKey] = article.knowledgeKey
                    it[ossKey] = article.ossKey
                    it[title] = article.title
                    it[description] = article.description
                    it[category] = article.category
                    it[articleSource] = article.source
                    it[relativePath] = article.relativePath
                    it[ext] = article.ext
                    it[contentHash] = article.contentHash
                    it[createdBy] = article.createdBy
                    it[origin] = article.origin
                    it[originSessionId] = article.originSessionId
                    it[originMessageId] = article.originMessageId
                    it[composedFrom] = article.composedFrom
                    it[status] = ArticleStatus.toWire(article.status)
                    it[updatedAt] = article.updatedAt
                }
                logger.debug("Updated knowledge article: {}", article.id)
            } else {
                table.insert {
                    it[id] = article.id
                    it[userId] = article.userId
                    it[createdBy] = article.createdBy
                    it[knowledgeKey] = article.knowledgeKey
                    it[ossKey] = article.ossKey
                    it[title] = article.title
                    it[description] = article.description
                    it[category] = article.category
                    it[articleSource] = article.source
                    it[relativePath] = article.relativePath
                    it[ext] = article.ext
                    it[contentHash] = article.contentHash
                    it[origin] = article.origin
                    it[originSessionId] = article.originSessionId
                    it[originMessageId] = article.originMessageId
                    it[composedFrom] = article.composedFrom
                    it[status] = ArticleStatus.toWire(article.status)
                    it[createdAt] = article.createdAt
                    it[updatedAt] = article.updatedAt
                }
                logger.debug("Inserted knowledge article: {}", article.id)
            }
        }
    }

    override suspend fun findByKey(userId: String, knowledgeKey: String): KnowledgeArticle? =
        suspendTransaction(db) {
            val table = Tables.KnowledgeArticleTable
            table.selectAll()
                .where {
                    (table.userId eq userId) and (table.knowledgeKey eq knowledgeKey)
                }
                .limit(1)
                .toList()
                .firstOrNull()
                ?.toArticle()
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

    override suspend fun findByKeys(
        userId: String,
        knowledgeKeys: Collection<String>,
    ): List<KnowledgeArticle> {
        if (knowledgeKeys.isEmpty()) return emptyList()
        return suspendTransaction(db) {
            val table = Tables.KnowledgeArticleTable
            table.selectAll()
                .where {
                    (table.userId eq userId) and (table.knowledgeKey inList knowledgeKeys.toList())
                }
                .toList()
                .map { it.toArticle() }
        }
    }

    override fun findAllInBuckets(
        userIds: Collection<String>,
        source: String?,
        category: String?,
        status: ArticleStatus?,
    ): Flow<KnowledgeArticle> = flow {
        if (userIds.isEmpty()) return@flow
        val table = Tables.KnowledgeArticleTable
        val rows = suspendTransaction(db) {
            var condition: Op<Boolean> = table.userId inList userIds.toList()
            if (!source.isNullOrBlank()) {
                condition = condition and (table.articleSource eq source)
            }
            if (!category.isNullOrBlank()) {
                condition = condition and (table.category eq category)
            }
            if (status != null) {
                condition = condition and (table.status eq ArticleStatus.toWire(status))
            }
            table.selectAll()
                .where { condition }
                .orderBy(table.updatedAt to SortOrder.DESC)
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

    /** Metadata-only delete; the caller owns OSS/RAG cleanup or a policy that denies orphans. */
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
            knowledgeKey = this[table.knowledgeKey],
            ossKey = this[table.ossKey],
            title = this[table.title],
            description = this[table.description],
            category = this[table.category],
            source = this[table.articleSource],
            relativePath = this[table.relativePath],
            ext = this[table.ext],
            contentHash = this[table.contentHash],
            origin = this[table.origin],
            originSessionId = this[table.originSessionId],
            originMessageId = this[table.originMessageId],
            composedFrom = this[table.composedFrom],
            status = ArticleStatus.fromColumn(this[table.status]),
            createdAt = this[table.createdAt],
            updatedAt = this[table.updatedAt]
        )
    }
}
