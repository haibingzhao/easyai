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
        fun `V1 creates all expected tables on fresh database`() {
            // Validates that the consolidated V1 schema contains all tables
            val jdbcUrl = "jdbc:h2:mem:flyway_tables_test;MODE=MYSQL;DB_CLOSE_DELAY=-1"

            val flyway = Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .load()
            val result = flyway.migrate()
            assertEquals("2", result.targetSchemaVersion)

            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                // Core tables
                conn.createStatement().executeQuery("SELECT id, user_id FROM agent").close()
                conn.createStatement().executeQuery("SELECT group_id FROM model_provider_config").close()
                conn.createStatement().executeQuery("SELECT id, name, protocol FROM model_config_group").close()
                // Team agent tables
                conn.createStatement().executeQuery("SELECT member_session_id FROM team_member_execution").close()
                conn.createStatement().executeQuery("SELECT id FROM team_round_record").close()
                // Swarm tables
                conn.createStatement().executeQuery("SELECT member_session_id FROM swarm_team_member_execution").close()
            }
        }
    }
}
