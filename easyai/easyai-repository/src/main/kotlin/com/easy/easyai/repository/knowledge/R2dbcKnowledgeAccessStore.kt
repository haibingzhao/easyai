package com.easy.easyai.repository.knowledge

import com.easy.easyai.core.knowledge.KnowledgeAccess
import com.easy.easyai.core.knowledge.KnowledgeAccessStore
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory

class R2dbcKnowledgeAccessStore(private val db: R2dbcDatabase) : KnowledgeAccessStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun grant(access: KnowledgeAccess) {
        val table = Tables.KnowledgeAccessTable
        suspendTransaction(db) {
            val existing = table.selectAll()
                .where {
                    (table.articleId eq access.articleId) and (table.granteeId eq access.granteeId)
                }
                .limit(1)
                .toList()
                .firstOrNull()

            if (existing != null) {
                table.update(where = { table.id eq existing[table.id] }) {
                    it[permission] = access.permission
                    it[grantedBy] = access.grantedBy
                    it[grantedAt] = access.grantedAt
                }
                logger.debug("Updated access grant: article={}, grantee={}", access.articleId, access.granteeId)
            } else {
                table.insert {
                    it[id] = access.id
                    it[articleId] = access.articleId
                    it[granteeId] = access.granteeId
                    it[permission] = access.permission
                    it[grantedBy] = access.grantedBy
                    it[grantedAt] = access.grantedAt
                }
                logger.debug("Inserted access grant: article={}, grantee={}", access.articleId, access.granteeId)
            }
        }
    }

    override suspend fun revoke(articleId: String, granteeId: String) {
        val table = Tables.KnowledgeAccessTable
        suspendTransaction(db) {
            val deleted = table.deleteWhere {
                (table.articleId eq articleId) and (table.granteeId eq granteeId)
            }
            if (deleted > 0) {
                logger.debug("Revoked access: article={}, grantee={}", articleId, granteeId)
            }
        }
    }

    override suspend fun listByArticle(articleId: String): List<KnowledgeAccess> =
        suspendTransaction(db) {
            val table = Tables.KnowledgeAccessTable
            table.selectAll()
                .where { table.articleId eq articleId }
                .toList()
                .map { it.toAccess() }
        }

    override suspend fun listByGrantee(granteeId: String): List<KnowledgeAccess> =
        suspendTransaction(db) {
            val table = Tables.KnowledgeAccessTable
            table.selectAll()
                .where { table.granteeId eq granteeId }
                .toList()
                .map { it.toAccess() }
        }

    override suspend fun hasPermission(articleId: String, granteeId: String, permission: String): Boolean =
        suspendTransaction(db) {
            val table = Tables.KnowledgeAccessTable
            table.selectAll()
                .where {
                    (table.articleId eq articleId) and
                        (table.granteeId eq granteeId) and
                        (table.permission eq permission)
                }
                .limit(1)
                .toList()
                .isNotEmpty()
        }

    override suspend fun deleteByArticle(articleId: String) {
        val table = Tables.KnowledgeAccessTable
        suspendTransaction(db) {
            val deleted = table.deleteWhere { table.articleId eq articleId }
            if (deleted > 0) {
                logger.debug("Deleted {} access grants for article {}", deleted, articleId)
            }
        }
    }

    private fun ResultRow.toAccess(): KnowledgeAccess {
        val table = Tables.KnowledgeAccessTable
        return KnowledgeAccess(
            id = this[table.id],
            articleId = this[table.articleId],
            granteeId = this[table.granteeId],
            permission = this[table.permission],
            grantedBy = this[table.grantedBy],
            grantedAt = this[table.grantedAt]
        )
    }
}
