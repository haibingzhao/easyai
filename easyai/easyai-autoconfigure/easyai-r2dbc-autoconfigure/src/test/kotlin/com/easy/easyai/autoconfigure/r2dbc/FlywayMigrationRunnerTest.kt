package com.easy.easyai.autoconfigure.r2dbc

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class FlywayMigrationRunnerTest {

    @Nested
    inner class `toJdbcUrl` {
        @Test
        fun `converts H2 file R2DBC URL to JDBC URL`() {
            val r2dbc = "r2dbc:h2:file:///home/user/.easyai/db/easyai;MODE=MYSQL"
            val jdbc = FlywayMigrationRunner.toJdbcUrl(r2dbc)
            assertEquals("jdbc:h2:file:///home/user/.easyai/db/easyai;MODE=MYSQL", jdbc)
        }

        @Test
        fun `converts PostgreSQL R2DBC URL to JDBC URL`() {
            val r2dbc = "r2dbc:postgresql://localhost:5432/easyai"
            val jdbc = FlywayMigrationRunner.toJdbcUrl(r2dbc)
            assertEquals("jdbc:postgresql://localhost:5432/easyai", jdbc)
        }

        @Test
        fun `converts postgres alias R2DBC URL to JDBC URL`() {
            val r2dbc = "r2dbc:postgres://user:pass@db.host:5432/mydb"
            val jdbc = FlywayMigrationRunner.toJdbcUrl(r2dbc)
            assertEquals("jdbc:postgresql://user:pass@db.host:5432/mydb", jdbc)
        }

        @Test
        fun `throws for unsupported URL format`() {
            assertThrows(IllegalStateException::class.java) {
                FlywayMigrationRunner.toJdbcUrl("r2dbc:mysql://localhost:3306/db")
            }
        }
    }

    @Nested
    inner class `isPersistentDb` {
        @Test
        fun `H2 in-memory is not persistent`() {
            val props = R2dbcProperties(url = "r2dbc:h2:mem:///easyai;MODE=MYSQL")
            val runner = FlywayMigrationRunner(props)
            assertTrue(!runner.isPersistentDb())
        }

        @Test
        fun `H2 file is persistent`() {
            val props = R2dbcProperties(url = "r2dbc:h2:file:///tmp/easyai;MODE=MYSQL")
            val runner = FlywayMigrationRunner(props)
            assertTrue(runner.isPersistentDb())
        }

        @Test
        fun `PostgreSQL is persistent`() {
            val props = R2dbcProperties(url = "r2dbc:postgresql://localhost:5432/easyai")
            val runner = FlywayMigrationRunner(props)
            assertTrue(runner.isPersistentDb())
        }
    }

    @Nested
    inner class `Migration SQL validation` {
        @Test
        fun `all migrations run successfully on H2 in-memory`() {
            // Validates that all V*.sql scripts are syntactically correct for H2
            val flyway = Flyway.configure()
                .dataSource("jdbc:h2:mem:flyway_test;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()

            val result = flyway.migrate()
            assertTrue(result.migrationsExecuted >= 2, "V1 and V2 should be executed")
            assertEquals("2", result.targetSchemaVersion)
        }

        @Test
        fun `V2 adds group_id column to a pre-existing model_provider_config table`() {
            // Simulates upgrading a legacy database created before the config-group feature:
            // model_provider_config already exists without group_id, then V1 (skipped by
            // IF NOT EXISTS) and V2 (ALTER TABLE ADD COLUMN) run on top of it
            val jdbcUrl = "jdbc:h2:mem:flyway_upgrade_test;MODE=MYSQL;DB_CLOSE_DELAY=-1"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.createStatement().execute(
                    """
                    CREATE TABLE model_provider_config (
                        id VARCHAR(255) PRIMARY KEY,
                        name VARCHAR(255) NOT NULL,
                        protocol VARCHAR(64) NOT NULL,
                        is_custom BOOLEAN NOT NULL,
                        base_url VARCHAR(512),
                        api_key TEXT,
                        model_id VARCHAR(255) NOT NULL,
                        model_name VARCHAR(255),
                        is_custom_model BOOLEAN NOT NULL,
                        enabled BOOLEAN NOT NULL,
                        options TEXT,
                        capabilities TEXT,
                        timeout_seconds BIGINT NOT NULL DEFAULT 600,
                        user_id VARCHAR(255) NOT NULL DEFAULT 'system',
                        created_at BIGINT NOT NULL,
                        updated_at BIGINT NOT NULL
                    )
                    """.trimIndent()
                )
            }

            val flyway = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
            val result = flyway.migrate()
            assertEquals("2", result.targetSchemaVersion)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.createStatement().executeQuery(
                    "SELECT group_id FROM model_provider_config"
                ).close()
                conn.createStatement().executeQuery(
                    "SELECT id, name, protocol FROM model_config_group"
                ).close()
            }
        }
    }
}
