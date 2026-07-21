package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.stereotype.Component
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Initializes R2DBC database connection and runs migrations asynchronously.
 *
 * Database connection is created eagerly during bean initialization.
 * Migrations run asynchronously via SmartLifecycle after context is ready.
 */
class R2dbcDatabaseInitializer(
    properties: R2dbcProperties,
    private val migration: DatabaseMigration = DatabaseMigration.defaultTables()
) : SmartLifecycle {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val database: R2dbcDatabase
    private val running = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        logger.info("Initializing R2DBC database at URL: {}", properties.url)
        database = R2dbcDatabase.connect(
            url = properties.url,
            user = properties.username,
            password = properties.password,
            manager = { TransactionManager(it) }
        ).also {
            logger.info("R2DBC database connection established")
        }
    }

    /**
     * Suspend function to run migrations.
     */
    private suspend fun runMigration() {
        migration.execute(database)
        logger.info("Database migration completed")
        initialized.set(true)
    }

    /**
     * Returns the R2dbcDatabase instance for async database operations.
     * This is the ONLY way to access the database - JDBC is strictly forbidden.
     */
    fun getDatabase(): R2dbcDatabase = database

    /**
     * Returns whether migration has completed.
     */
    fun isInitialized(): Boolean = initialized.get()

    // SmartLifecycle implementation for async migration
    override fun start() {
        if (running.compareAndSet(false, true)) {
            scope.launch {
                try {
                    runMigration()
                } catch (e: Exception) {
                    logger.error("Failed to run database migration", e)
                }
            }
        }
    }

    override fun stop() {
        running.set(false)
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MIN_VALUE // Start early, before other beans need the database
}
