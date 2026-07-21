package com.easy.easyai.web.setup

import com.easy.easyai.autoconfigure.r2dbc.DatabaseConfig
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

/**
 * REST controller for runtime database configuration management.
 *
 * Active in Normal Mode (after database is configured).
 * Allows authenticated users to view and modify database settings.
 *
 * Endpoints:
 * - GET  /api/system/database       - Returns current database info (masked)
 * - POST /api/system/database/test  - Tests a new database connection
 * - POST /api/system/database/apply - Saves new config (restart required)
 */
@RestController
@RequestMapping("/api/system/database")
class SystemDatabaseController {

    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping
    fun getDatabaseInfo(): Mono<Map<String, Any?>> = mono {
        val config = DatabaseConfig.load()
        if (config != null) {
            mapOf(
                "configured" to true,
                "dbType" to config.dbType,
                "info" to config.toDisplayInfo()
            )
        } else {
            // Fallback: infer from Spring properties (configured via application.properties)
            mapOf(
                "configured" to true,
                "dbType" to "unknown",
                "info" to mapOf("dbType" to "unknown", "url" to "Configured via application properties")
            )
        }
    }

    @PostMapping("/test")
    fun testConnection(@RequestBody request: DatabaseSetupRequest): Mono<Map<String, Any?>> = mono {
        try {
            val config = request.toDatabaseConfig()
            val r2dbc = config.toR2dbcProperties()

            val options = ConnectionFactoryOptions.parse(r2dbc.url)
                .mutate()
                .option(ConnectionFactoryOptions.USER, r2dbc.username)
                .option(ConnectionFactoryOptions.PASSWORD, r2dbc.password)
                .build()
            val factory = ConnectionFactories.get(options)
            val connection = Mono.from(factory.create()).block()
            if (connection != null) {
                Mono.from(connection.close()).block()
            }

            logger.info("Database connection test successful (dbType={})", config.dbType)
            mapOf("success" to true, "message" to "Connection successful")
        } catch (e: Exception) {
            logger.warn("Database connection test failed: {}", e.message)
            mapOf("success" to false, "message" to (e.message ?: "Connection failed"))
        }
    }

    @PostMapping("/apply")
    fun apply(@RequestBody request: DatabaseSetupRequest): Mono<Map<String, Any?>> = mono {
        try {
            val config = request.toDatabaseConfig()

            // Ensure H2 directory exists
            if (config.dbType == "h2") {
                val dir = config.h2?.dir
                if (dir != null) {
                    val resolvedDir = dir.replace("~", System.getProperty("user.home"))
                    java.nio.file.Files.createDirectories(java.nio.file.Path.of(resolvedDir))
                }
            }

            // Save configuration
            DatabaseConfig.save(config)
            logger.info("Database configuration updated (dbType={}). Restart required.", config.dbType)

            mapOf(
                "success" to true,
                "message" to "Configuration saved. Backend restart required to apply changes.",
                "restartRequired" to true
            )
        } catch (e: Exception) {
            logger.error("Failed to apply database configuration", e)
            mapOf("success" to false, "message" to (e.message ?: "Failed to save configuration"))
        }
    }
}
