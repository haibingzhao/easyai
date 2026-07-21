package com.easy.easyai.repository.goal

import com.easy.easyai.core.goal.GoalState
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.repository.database.Tables
import com.easy.easyai.repository.database.UserScope
import kotlinx.coroutines.flow.firstOrNull
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.select
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.jetbrains.exposed.v1.r2dbc.update
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import com.easy.easyai.common.util.SharedObjectMapper

/**
 * R2DBC-based implementation of [GoalStore].
 * Stores goal state as a JSON column on the Session table (`goal_json`).
 *
 * Schema: Session.goal_json TEXT (nullable)
 * - null means no goal is set
 * - JSON-serialized GoalState when a goal is active
 *
 * This approach keeps the goal lifecycle tied to the session with minimal schema changes.
 */
class SqlGoalStore(
    private val db: R2dbcDatabase,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : GoalStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun getGoal(sessionId: String, userId: String): GoalState? {
        return suspendTransaction(db) {
            val row = Tables.Session
                .select(Tables.Session.goalJson)
                .where {
                    (Tables.Session.id eq sessionId) and
                        UserScope.filterStrict(Tables.Session.userId, userId)
                }
                .limit(1)
                .firstOrNull()
                ?: return@suspendTransaction null
            val json: String = row[Tables.Session.goalJson] ?: return@suspendTransaction null
            try {
                objectMapper.readValue(json, GoalState::class.java)
            } catch (e: Exception) {
                logger.warn("Failed to deserialize goal state for session {}: {}", sessionId, e.message)
                null
            }
        }
    }

    override suspend fun saveGoal(goal: GoalState, userId: String) {
        suspendTransaction(db) {
            val json = objectMapper.writeValueAsString(goal)
            Tables.Session.update(
                where = {
                    (Tables.Session.id eq goal.sessionId) and
                        UserScope.filterStrict(Tables.Session.userId, userId)
                }
            ) {
                it[Tables.Session.goalJson] = json
                it[Tables.Session.updatedAt] = System.currentTimeMillis()
            }
        }
        logger.debug("Saved goal state for session {}: status={}", goal.sessionId, goal.status)
    }

    override suspend fun deleteGoal(sessionId: String, userId: String) {
        suspendTransaction(db) {
            Tables.Session.update(
                where = {
                    (Tables.Session.id eq sessionId) and
                        UserScope.filterStrict(Tables.Session.userId, userId)
                }
            ) {
                it[Tables.Session.goalJson] = null as String?
                it[Tables.Session.updatedAt] = System.currentTimeMillis()
            }
        }
        logger.debug("Deleted goal state for session {}", sessionId)
    }
}
