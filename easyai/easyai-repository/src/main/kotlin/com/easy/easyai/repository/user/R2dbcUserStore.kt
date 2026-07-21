package com.easy.easyai.repository.user

import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.model.User
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.update
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

/**
 * R2DBC-based implementation of [UserStore].
 * Uses Exposed R2DBC for pure async database operations.
 */
class R2dbcUserStore(private val db: R2dbcDatabase) : UserStore {
    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun findByUsername(username: String): User? {
        return suspendTransaction(db) {
            Tables.UserTable
                .selectAll()
                .where { Tables.UserTable.username eq username }
                .limit(1)
                .firstOrNull()
                ?.toUser()
        }
    }

    override suspend fun findById(id: String): User? {
        return suspendTransaction(db) {
            Tables.UserTable
                .selectAll()
                .where { Tables.UserTable.id eq id }
                .limit(1)
                .firstOrNull()
                ?.toUser()
        }
    }

    override suspend fun save(user: User): User {
        suspendTransaction(db) {
            Tables.UserTable.insert {
                it[id] = user.id
                it[username] = user.username
                it[displayName] = user.displayName
                it[email] = user.email
                it[passwordHash] = user.passwordHash
                it[avatar] = user.avatar
                it[createdAt] = user.createdAt
                it[updatedAt] = user.updatedAt
            }
            logger.info("Saved user: {} ({})", user.username, user.id)
        }
        return user
    }

    override suspend fun update(user: User): User {
        suspendTransaction(db) {
            Tables.UserTable.update(
                where = { Tables.UserTable.id eq user.id }
            ) {
                it[displayName] = user.displayName
                it[email] = user.email
                it[avatar] = user.avatar
                it[passwordHash] = user.passwordHash
                it[updatedAt] = System.currentTimeMillis()
            }
            logger.info("Updated user: {}", user.id)
        }
        return user
    }

    override suspend fun count(): Long {
        return suspendTransaction(db) {
            Tables.UserTable.selectAll().count()
        }
    }

    private fun org.jetbrains.exposed.v1.core.ResultRow.toUser(): User = User(
        id = this[Tables.UserTable.id],
        username = this[Tables.UserTable.username],
        displayName = this[Tables.UserTable.displayName],
        email = this[Tables.UserTable.email],
        passwordHash = this[Tables.UserTable.passwordHash],
        avatar = this[Tables.UserTable.avatar],
        createdAt = this[Tables.UserTable.createdAt],
        updatedAt = this[Tables.UserTable.updatedAt]
    )
}
