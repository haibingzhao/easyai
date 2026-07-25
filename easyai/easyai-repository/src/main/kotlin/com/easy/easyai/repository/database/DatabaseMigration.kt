package com.easy.easyai.repository.database

import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.SchemaUtils
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory

/**
 * Database migration utility using Exposed R2DBC SchemaUtils.
 *
 * Scope: H2 in-memory databases only (tests/dev — always fresh on startup).
 * For persistent databases (H2 file, PostgreSQL), schema is managed by Flyway
 * via [com.easy.easyai.autoconfigure.r2dbc.FlywayMigrationRunner].
 */
class DatabaseMigration(
    private val tables: Array<Table>
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Execute migration: create tables if not exist.
     */
    @Suppress("DEPRECATION")
    suspend fun execute(db: R2dbcDatabase) {
        suspendTransaction(db) {
            SchemaUtils.createMissingTablesAndColumns(*tables)
            // Note: createMissingTablesAndColumns does NOT create index() declarations
            // for pre-existing tables. For H2 in-memory (always fresh on startup),
            // this is not an issue — tables are always created from scratch with indexes.
            // For persistent DBs, use statementsRequiredToActualizeScheme or a migration tool.
            logger.info("Database tables created/verified")
        }
    }

    companion object {
        fun defaultTables(): DatabaseMigration = DatabaseMigration(
            arrayOf(
                Tables.UserTable,
                Tables.RefreshTokenTable,
                Tables.AgentTable,
                Tables.AgentToolTable,
                Tables.Project,
                Tables.Session,
                Tables.Message,
                Tables.ModelConfigGroupTable,
                Tables.ModelProviderConfigTable,
                Tables.TodoTable,
                Tables.PermissionRuleTable,
                Tables.McpServerConfigTable,
                Tables.UserCommandTable,
                Tables.SwarmRunTable,
                Tables.SwarmTaskTable,
                Tables.SwarmDeliberationHistoryTable,
                Tables.SwarmDeliberationVerdictTable,
                Tables.SwarmEscalationHistoryTable,
                Tables.SwarmTeamMemberExecutionTable,
                Tables.SwarmTeamRoundRecordTable,
                Tables.TeamMemberExecutionTable,
                Tables.TeamRoundRecordTable,
                Tables.SwarmPresetTable
            )
        )
    }
}

/**
 * Suspend function wrapper for running database operations in a transaction.
 * Uses Exposed's coroutine support for R2DBC.
 */
suspend fun <T> asyncTransaction(db: R2dbcDatabase, block: suspend () -> T): T {
    return suspendTransaction(db) {
        block()
    }
}