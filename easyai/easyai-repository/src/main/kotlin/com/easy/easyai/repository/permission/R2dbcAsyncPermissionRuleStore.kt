package com.easy.easyai.repository.permission

import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.permission.PermissionRuleStore
import com.easy.easyai.repository.database.Tables
import kotlinx.coroutines.flow.toList
import org.jetbrains.exposed.v1.core.SortOrder
import org.jetbrains.exposed.v1.core.and
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.batchInsert
import org.jetbrains.exposed.v1.r2dbc.deleteWhere
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import java.util.UUID

/**
 * R2DBC-backed implementation of [PermissionRuleStore].
 * Uses Exposed R2DBC for pure async database operations.
 */
class R2dbcAsyncPermissionRuleStore(
    private val db: R2dbcDatabase
) : PermissionRuleStore {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun loadRules(projectId: String): List<PermissionRule> {
        return suspendTransaction(db) {
            Tables.PermissionRuleTable
                .selectAll()
                .where { Tables.PermissionRuleTable.projectId eq projectId }
                .orderBy(Tables.PermissionRuleTable.createdAt to SortOrder.ASC)
                .toList()
                .map { row ->
                    PermissionRule(
                        permission = row[Tables.PermissionRuleTable.permission],
                        pattern = row[Tables.PermissionRuleTable.pattern],
                        action = PermissionAction.valueOf(row[Tables.PermissionRuleTable.action])
                    )
                }
        }
    }

    override suspend fun saveRules(projectId: String, rules: List<PermissionRule>) {
        suspendTransaction(db) {
            // Full replacement: delete existing rules for this project
            Tables.PermissionRuleTable.deleteWhere {
                Tables.PermissionRuleTable.projectId eq projectId
            }

            // Batch insert new rules
            val now = System.currentTimeMillis()
            Tables.PermissionRuleTable.batchInsert(rules) { rule ->
                this[Tables.PermissionRuleTable.id] = UUID.randomUUID().toString()
                this[Tables.PermissionRuleTable.projectId] = projectId
                this[Tables.PermissionRuleTable.permission] = rule.permission
                this[Tables.PermissionRuleTable.pattern] = rule.pattern
                this[Tables.PermissionRuleTable.action] = rule.action.name
                this[Tables.PermissionRuleTable.createdAt] = now
            }
            logger.debug("Saved {} permission rules for project {}", rules.size, projectId)
        }
    }

    override suspend fun addRule(projectId: String, rule: PermissionRule) {
        suspendTransaction(db) {
            Tables.PermissionRuleTable.insert {
                it[id] = UUID.randomUUID().toString()
                it[Tables.PermissionRuleTable.projectId] = projectId
                it[Tables.PermissionRuleTable.permission] = rule.permission
                it[Tables.PermissionRuleTable.pattern] = rule.pattern
                it[Tables.PermissionRuleTable.action] = rule.action.name
                it[Tables.PermissionRuleTable.createdAt] = System.currentTimeMillis()
            }
            logger.debug("Added permission rule: {} {}={} for project {}", rule.action, rule.permission, rule.pattern, projectId)
        }
    }

    override suspend fun deleteRule(projectId: String, permission: String, pattern: String) {
        suspendTransaction(db) {
            Tables.PermissionRuleTable.deleteWhere {
                (Tables.PermissionRuleTable.projectId eq projectId) and
                (Tables.PermissionRuleTable.permission eq permission) and
                (Tables.PermissionRuleTable.pattern eq pattern)
            }
            logger.debug("Deleted permission rule: {}={} for project {}", permission, pattern, projectId)
        }
    }
}
