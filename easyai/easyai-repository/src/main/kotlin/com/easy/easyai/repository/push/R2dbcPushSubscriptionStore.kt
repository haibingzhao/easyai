package com.easy.easyai.repository.push

import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.ResultRow
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insertIgnore
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * R2DBC implementation of [PushSubscriptionStore].
 *
 * The rebind keeps the original `id` / `created_at`; the unique index on `endpoint`
 * (Flyway V29) is what the database enforces.
 */
class R2dbcPushSubscriptionStore(private val db: R2dbcDatabase) : PushSubscriptionStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun upsert(subscription: PushSubscription) {
        val now = System.currentTimeMillis()
        val record = subscription.copy(
            id = subscription.id.ifBlank { UUID.randomUUID().toString() },
            createdAt = subscription.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = now,
        )
        val table = Tables.PushSubscriptionTable
        suspendTransaction(db) {
            // Deliberately not `upsert`: Exposed routes H2's MySQL-compatibility mode (the
            // production H2 URL carries MODE=MYSQL) through the MySQL function provider, which
            // rejects an explicit conflict key. Insert-or-ignore plus a keyed update keeps the
            // rebind semantics on every supported database.
            table.insertIgnore {
                it[id] = record.id
                it[userId] = record.userId
                it[endpoint] = record.endpoint
                it[p256dh] = record.p256dh
                it[auth] = record.auth
                it[userAgent] = record.userAgent
                it[createdAt] = record.createdAt
                it[updatedAt] = record.updatedAt
            }
            table.update({ table.endpoint eq record.endpoint }) {
                it[userId] = record.userId
                it[p256dh] = record.p256dh
                it[auth] = record.auth
                it[userAgent] = record.userAgent
                it[updatedAt] = record.updatedAt
            }
        }
        logger.debug("Upserted push subscription for user {}", record.userId)
    }

    override suspend fun findByUserId(userId: String, limit: Int): List<PushSubscription> {
        return suspendTransaction(db) {
            Tables.PushSubscriptionTable.selectAll()
                .where { Tables.PushSubscriptionTable.userId eq userId }
                .orderBy(Tables.PushSubscriptionTable.updatedAt to SortOrder.DESC)
                .limit(limit.coerceAtLeast(1))
                .toList()
                .map { it.toPushSubscription() }
        }
    }

    override suspend fun findByEndpoint(endpoint: String): PushSubscription? {
        return suspendTransaction(db) {
            Tables.PushSubscriptionTable.selectAll()
                .where { Tables.PushSubscriptionTable.endpoint eq endpoint }
                .toList()
                .firstOrNull()
                ?.toPushSubscription()
        }
    }

    override suspend fun countByUserId(userId: String): Int {
        return suspendTransaction(db) {
            Tables.PushSubscriptionTable.selectAll()
                .where { Tables.PushSubscriptionTable.userId eq userId }
                .count()
                .toInt()
        }
    }

    override suspend fun deleteByUserAndEndpoint(userId: String, endpoint: String): Int {
        return suspendTransaction(db) {
            Tables.PushSubscriptionTable.deleteWhere {
                (Tables.PushSubscriptionTable.userId eq userId) and
                    (Tables.PushSubscriptionTable.endpoint eq endpoint)
            }
        }
    }

    override suspend fun deleteById(id: String): Int {
        return suspendTransaction(db) {
            Tables.PushSubscriptionTable.deleteWhere { Tables.PushSubscriptionTable.id eq id }
        }
    }

    private fun ResultRow.toPushSubscription(): PushSubscription = PushSubscription(
        id = this[Tables.PushSubscriptionTable.id],
        userId = this[Tables.PushSubscriptionTable.userId],
        endpoint = this[Tables.PushSubscriptionTable.endpoint],
        p256dh = this[Tables.PushSubscriptionTable.p256dh],
        auth = this[Tables.PushSubscriptionTable.auth],
        userAgent = this[Tables.PushSubscriptionTable.userAgent],
        createdAt = this[Tables.PushSubscriptionTable.createdAt],
        updatedAt = this[Tables.PushSubscriptionTable.updatedAt],
    )
}
