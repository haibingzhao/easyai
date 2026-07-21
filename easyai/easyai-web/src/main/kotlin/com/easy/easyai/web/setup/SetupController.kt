package com.easy.easyai.web.setup

import com.easy.easyai.autoconfigure.r2dbc.DatabaseConfig
import io.r2dbc.spi.ConnectionFactories
import io.r2dbc.spi.ConnectionFactoryOptions
import kotlinx.coroutines.reactor.mono
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.*
import reactor.core.publisher.Mono

/**
 * REST controller for initial database setup.
 *
 * Only active in Setup Mode (when no database is configured).
 * Provides endpoints to test database connections and apply configuration.
 *
 * Endpoints:
 * - GET  /api/setup/status           - Returns current setup status
 * - POST /api/setup/test-connection  - Tests a database connection
 * - POST /api/setup/apply            - Saves config and triggers restart
 */
@RestController
@RequestMapping("/api/setup")
class SetupController {

    private val logger = LoggerFactory.getLogger(javaClass)

    @GetMapping("/status")
    fun status(): Mono<Map<String, Any?>> = mono {
        val config = DatabaseConfig.load()
        if (config != null) {
            mapOf(
                "mode" to "normal",
                "dbType" to config.dbType
            )
        } else {
            mapOf(
                "mode" to "setup",
                "dbType" to null
            )
        }
    }

    @PostMapping("/test-connection")
    fun testConnection(@RequestBody request: DatabaseSetupRequest): Mono<Map<String, Any?>> = mono {
        try {
            val config = request.toDatabaseConfig()
            val r2dbc = config.toR2dbcProperties()

            // Attempt to create a connection factory with credentials and get a connection
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
            logger.info("Database configuration applied (dbType={}). Restart required.", config.dbType)

            mapOf(
                "success" to true,
                "message" to "Configuration saved. Backend will restart.",
                "restartRequired" to true
            )
        } catch (e: Exception) {
            logger.error("Failed to apply database configuration", e)
            mapOf("success" to false, "message" to (e.message ?: "Failed to save configuration"))
        }
    }
}

/**
 * Request body for database setup endpoints.
 */
data class DatabaseSetupRequest(
    val dbType: String = "h2",
    val h2Dir: String? = null,
    val postgresUrl: String? = null,
    val postgresUsername: String? = null,
    val postgresPassword: String? = null
) {
    fun toDatabaseConfig(): DatabaseConfig {
        return when (dbType) {
            "postgres" -> DatabaseConfig(
                dbType = "postgres",
                h2 = null,
                postgres = DatabaseConfig.PostgresConfig(
                    url = postgresUrl ?: throw IllegalArgumentException("PostgreSQL URL is required"),
                    username = postgresUsername ?: "",
                    password = postgresPassword ?: ""
                )
            )
            else -> DatabaseConfig(
                dbType = "h2",
                h2 = DatabaseConfig.H2Config(dir = h2Dir),
                postgres = null
            )
        }
    }
}
