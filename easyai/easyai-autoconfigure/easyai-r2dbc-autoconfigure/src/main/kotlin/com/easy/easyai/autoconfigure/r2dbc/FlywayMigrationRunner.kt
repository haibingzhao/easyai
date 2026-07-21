package com.easy.easyai.autoconfigure.r2dbc

import org.flywaydb.core.Flyway
import org.slf4j.LoggerFactory

/**
 * Runs Flyway schema migrations for persistent databases (H2 file, PostgreSQL).
 *
 * Flyway requires a JDBC connection (not R2DBC). This runner derives the JDBC URL
 * from the configured R2DBC URL automatically, following the zero-config principle.
 *
 * For H2 in-memory databases (tests/dev), Flyway is skipped — [DatabaseMigration]
 * with SchemaUtils handles fresh schema creation on each startup.
 *
 * Reference pattern: easy-rss-server-kt DatabaseConfig.kt (manual Flyway bean in reactive project).
 */
class FlywayMigrationRunner(private val properties: R2dbcProperties) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Whether the configured database is persistent (not H2 in-memory).
     * Flyway only runs for persistent databases.
     */
    fun isPersistentDb(): Boolean {
        return !properties.url.contains("h2:mem")
    }

    /**
     * Run Flyway migration synchronously.
     * Safe to call on startup thread — after first run, only reads flyway_schema_history (sub-ms).
     */
    fun migrate() {
        val jdbcUrl = toJdbcUrl(properties.url)
        logger.info("Running Flyway migration with JDBC URL: {}", jdbcUrl)

        val flyway = Flyway.configure()
            .dataSource(jdbcUrl, properties.username, properties.password)
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .load()

        val result = flyway.migrate()
        logger.info(
            "Flyway migration completed: {} migration(s) applied, schema at version {}",
            result.migrationsExecuted,
            result.targetSchemaVersion ?: "none"
        )
    }

    companion object {
        /**
         * Derive JDBC URL from R2DBC URL.
         *
         * Supported mappings:
         * - `r2dbc:h2:file:///path;MODE=MYSQL` → `jdbc:h2:file:///path;MODE=MYSQL`
         * - `r2dbc:postgresql://host:port/db` → `jdbc:postgresql://host:port/db`
         *
         * @throws IllegalStateException for unsupported URL formats
         */
        @JvmStatic
        fun toJdbcUrl(r2dbcUrl: String): String {
            return when {
                r2dbcUrl.startsWith("r2dbc:h2:file:") ->
                    r2dbcUrl.replaceFirst("r2dbc:h2:file:", "jdbc:h2:file:")

                r2dbcUrl.startsWith("r2dbc:postgresql:") ->
                    r2dbcUrl.replaceFirst("r2dbc:postgresql:", "jdbc:postgresql:")

                r2dbcUrl.startsWith("r2dbc:postgres:") ->
                    r2dbcUrl.replaceFirst("r2dbc:postgres:", "jdbc:postgresql:")

                else -> throw IllegalStateException(
                    "Cannot derive JDBC URL from R2DBC URL: $r2dbcUrl. " +
                        "Supported formats: r2dbc:h2:file:..., r2dbc:postgresql://..."
                )
            }
        }
    }
}
