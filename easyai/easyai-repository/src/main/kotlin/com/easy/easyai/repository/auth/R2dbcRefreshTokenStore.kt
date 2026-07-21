package com.easy.easyai.repository.auth

import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.model.RefreshToken
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

/**
 * R2DBC-based implementation of [RefreshTokenStore].
 * Tokens are stored as SHA-256 hashes, never as raw values.
 */
class R2dbcRefreshTokenStore(private val db: R2dbcDatabase) : RefreshTokenStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun save(token: RefreshToken): RefreshToken {
        suspendTransaction(db) {
            Tables.RefreshTokenTable.insert {
                it[id] = token.id
                it[userId] = token.userId
                it[tokenHash] = token.tokenHash
                it[expiresAt] = token.expiresAt
                it[createdAt] = token.createdAt
            }
            logger.debug("Saved refresh token for user: {}", token.userId)
        }
        return token
    }

    override suspend fun findByTokenHash(tokenHash: String): RefreshToken? {
        return suspendTransaction(db) {
            Tables.RefreshTokenTable
                .selectAll()
                .where { Tables.RefreshTokenTable.tokenHash eq tokenHash }
                .limit(1)
                .firstOrNull()
                ?.let { row ->
                    RefreshToken(
                        id = row[Tables.RefreshTokenTable.id],
                        userId = row[Tables.RefreshTokenTable.userId],
                        tokenHash = row[Tables.RefreshTokenTable.tokenHash],
                        expiresAt = row[Tables.RefreshTokenTable.expiresAt],
                        createdAt = row[Tables.RefreshTokenTable.createdAt]
                    )
                }
        }
    }

    override suspend fun delete(id: String) {
        suspendTransaction(db) {
            Tables.RefreshTokenTable.deleteWhere { Tables.RefreshTokenTable.id eq id }
            logger.debug("Deleted refresh token: {}", id)
        }
    }

    override suspend fun deleteByUserId(userId: String) {
        suspendTransaction(db) {
            Tables.RefreshTokenTable.deleteWhere { Tables.RefreshTokenTable.userId eq userId }
            logger.info("Deleted all refresh tokens for user: {}", userId)
        }
    }
}
