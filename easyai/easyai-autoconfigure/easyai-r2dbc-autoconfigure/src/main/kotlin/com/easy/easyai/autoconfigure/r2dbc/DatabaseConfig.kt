package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.common.util.SharedObjectMapper
import org.slf4j.LoggerFactory
import java.nio.file.Files
import java.nio.file.Path

/**
 * Persistent database configuration stored at `~/.easyai/db-config.json`.
 *
 * Supports H2 (embedded, zero-config) and PostgreSQL.
 * Used by [DatabaseConfigEnvironmentPostProcessor] to resolve database settings
 * before Spring context initialization.
 */
data class DatabaseConfig(
    val dbType: String = "h2",
    val h2: H2Config? = H2Config(),
    val postgres: PostgresConfig? = null
) {
    data class H2Config(
        val dir: String? = null
    )

    data class PostgresConfig(
        val url: String = "",
        val username: String = "",
        val password: String = ""
    )

    companion object {
        private val logger = LoggerFactory.getLogger(DatabaseConfig::class.java)

        /** Default config file location: `~/.easyai/db-config.json` */
        fun defaultConfigPath(): Path {
            return Path.of(System.getProperty("user.home"), ".easyai", "db-config.json")
        }

        /**
         * Load database config from the given path.
         * Returns null if the file does not exist or cannot be parsed.
         */
        fun load(path: Path = defaultConfigPath()): DatabaseConfig? {
            if (!Files.exists(path)) return null
            return try {
                val content = Files.readString(path)
                SharedObjectMapper.instance.readValue(content, DatabaseConfig::class.java)
            } catch (e: Exception) {
                logger.warn("Failed to parse db-config.json at {}: {}", path, e.message)
                null
            }
        }

        /**
         * Save database config to the given path.
         * Creates parent directories if they don't exist.
         */
        fun save(config: DatabaseConfig, path: Path = defaultConfigPath()) {
            Files.createDirectories(path.parent)
            val content = SharedObjectMapper.instance.writerWithDefaultPrettyPrinter()
                .writeValueAsString(config)
            Files.writeString(path, content)
            logger.info("Database config saved to {}", path)
        }
    }

    /**
     * Resolve this config into R2DBC connection properties.
     */
    fun toR2dbcProperties(): R2dbcProperties {
        return when (dbType) {
            "postgres" -> {
                val pg = postgres ?: throw IllegalStateException("dbType=postgres but postgres config is missing")
                R2dbcProperties(
                    enabled = true,
                    url = pg.url,
                    username = pg.username,
                    password = pg.password
                )
            }
            else -> {
                // H2 embedded
                val dir = h2?.dir ?: Path.of(System.getProperty("user.home"), ".easyai", "db").toString()
                val resolvedDir = dir.replace("~", System.getProperty("user.home"))
                val dbPath = Path.of(resolvedDir, "easyai").toString().replace("\\", "/")
                R2dbcProperties(
                    enabled = true,
                    url = "r2dbc:h2:file:///$dbPath;MODE=MYSQL",
                    username = "sa",
                    password = ""
                )
            }
        }
    }

    /**
     * Returns a display-friendly description of this database config (password masked).
     */
    fun toDisplayInfo(): Map<String, String?> {
        return when (dbType) {
            "postgres" -> mapOf(
                "dbType" to "postgres",
                "url" to postgres?.url,
                "username" to postgres?.username
            )
            else -> mapOf(
                "dbType" to "h2",
                "url" to toR2dbcProperties().url,
                "username" to "sa"
            )
        }
    }
}
