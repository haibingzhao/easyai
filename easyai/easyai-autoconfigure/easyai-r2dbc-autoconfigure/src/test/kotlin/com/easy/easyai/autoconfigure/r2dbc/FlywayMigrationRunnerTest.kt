package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.repository.database.Tables
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException

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
            assertTrue(result.migrationsExecuted >= 6, "V1 through V6 should be executed")
            // Asserted against what Flyway actually applied, so adding V7 never means touching this file.
            assertEquals(maxVersionOnClasspath(flyway), result.targetSchemaVersion)
        }

        @Test
        fun `migrated skill schema supports every current mapped column`() {
            val jdbcUrl = "jdbc:h2:mem:flyway_skill_mapping;MODE=MYSQL;DB_CLOSE_DELAY=-1"
            Flyway.configure()
                .dataSource(jdbcUrl, "sa", "")
                .locations("classpath:db/migration")
                .load()
                .migrate()
            DriverManager.getConnection(jdbcUrl, "sa", "").use { connection ->
                connection.executeSkillInsert("alice", "report")
                val columns = Tables.SkillTable.columns.joinToString(", ") { it.name }
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT $columns FROM skill").use { result ->
                        assertTrue(result.next())
                        assertEquals("alice", result.getString(Tables.SkillTable.userId.name))
                        assertEquals("report", result.getString(Tables.SkillTable.name.name))
                        assertEquals("", result.getString(Tables.SkillTable.projectHash.name))
                        assertEquals("PENDING_INDEX", result.getString(Tables.SkillTable.syncState.name))
                        assertEquals(0L, result.getLong(Tables.SkillTable.revision.name))
                    }
                }
            }
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
            assertEquals(maxVersionOnClasspath(flyway), result.targetSchemaVersion)

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
                // Skill catalog table (V3): the columns the retrieval slices are derived from
                conn.createStatement().executeQuery(
                    "SELECT id, name, source, version, checksum, enabled, install_path, origin, user_id " +
                        "FROM skill"
                ).close()
                // Storage settings table (V4): per-user object-storage configuration rows
                conn.createStatement().executeQuery(
                    "SELECT user_id, enabled, storage_type, endpoint, bucket, access_key_id, access_key_secret, local_dir " +
                        "FROM storage_settings"
                ).close()
                conn.createStatement().executeQuery(
                    "SELECT id, name, install_path, user_id, project_hash, index_project_path, " +
                        "indexed_checksum, sync_state, revision, next_attempt_at, last_error FROM skill"
                ).close()
                conn.createStatement().use { statement ->
                    val indexes = mutableSetOf<String>()
                    statement.executeQuery(
                        "SELECT DISTINCT INDEX_NAME FROM INFORMATION_SCHEMA.INDEXES " +
                            "WHERE UPPER(TABLE_NAME) = 'SKILL'"
                    ).use { rs -> while (rs.next()) indexes += rs.getString(1).uppercase() }
                    assertTrue(
                        "UQ_SKILL_USER_NAME_HASH" in indexes,
                        "V6 must leave the triple unique index in place, got: $indexes"
                    )
                    assertTrue(
                        "UQ_SKILL_USER_NAME" !in indexes,
                        "the old (user_id, name) unique index would collapse two projects' same-named skills again, got: $indexes"
                    )
                }
            }
        }

        @Test
        fun `skills can share a name across owners and projects but not within one triple`() {
            val jdbcUrl = "jdbc:h2:mem:flyway_skill_names;MODE=MYSQL;DB_CLOSE_DELAY=-1"
            DriverManager.getConnection(jdbcUrl, "sa", "").use { conn ->
                conn.applySkillSchema()
                conn.applyProjectScopedIdentity()
                conn.executeSkillInsert("alice", "pdf-report")
                conn.executeSkillInsert("bob", "pdf-report")
                // Same owner, same name, another project: the row V3 could not express.
                conn.executeSkillInsert("alice", "pdf-report", id = "row-3", projectHash = "1234abcd5678ef90")

                // A different id, so the only constraint this can trip is the (user_id, name, project_hash) one.
                val error = assertThrows(SQLException::class.java) {
                    conn.executeSkillInsert("alice", "pdf-report", id = "row-4")
                }
                assertTrue("UQ_SKILL" in error.message!!.uppercase(), "got: ${error.message}")
            }
        }

        @Test
        fun `the skill migration can be replayed without error`() {
            // Every statement is `IF NOT EXISTS`, which is what lets a partially migrated or restored
            // database come up: replaying the scripts must not raise a duplicate-object error.
            val statements = skillStatements()
            assertTrue(statements.size >= 3, "expected the table plus two indexes, got $statements")

            DriverManager.getConnection("jdbc:h2:mem:flyway_skill_replay;MODE=MYSQL;DB_CLOSE_DELAY=-1", "sa", "")
                .use { conn ->
                    repeat(2) {
                        conn.applySkillSchema()
                        conn.applyProjectScopedIdentity()
                    }
                }
        }

        private fun statementsOf(resource: String): List<String> {
            val sql = this.javaClass.getResourceAsStream(resource)
                ?.bufferedReader()?.readText()
                ?: error("$resource is not on the test classpath")
            // Comments may hold semicolons, so drop them before splitting on statement boundaries.
            return sql.lineSequence()
                .filterNot { it.trimStart().startsWith("--") }
                .joinToString("\n")
                .split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        }

        private fun skillStatements(): List<String> = statementsOf(SKILL_MIGRATION)

        private fun Connection.applySkillSchema() = skillStatements().forEach { createStatement().execute(it) }

        private fun Connection.applyProjectScopedIdentity() =
            statementsOf(PROJECT_SCOPED_IDENTITY).forEach { createStatement().execute(it) }

        private fun Connection.executeSkillInsert(
            userId: String,
            name: String,
            id: String = "$userId-$name",
            projectHash: String = ""
        ) {
            createStatement().execute(
                "INSERT INTO skill (id, name, checksum, install_path, created_at, updated_at, user_id, project_hash) " +
                    "VALUES ('$id', '$name', 'a', '/home/$userId/.easyai/skills/$name', 1, 1, '$userId', '$projectHash')"
            )
        }
    }

    companion object {
        private const val SKILL_MIGRATION = "/db/migration/V3__create_skill_table.sql"
        private const val PROJECT_SCOPED_IDENTITY = "/db/migration/V6__skill_project_scoped_identity.sql"

        /** The newest versioned script Flyway sees, so this file never lags behind a new migration. */
        private fun maxVersionOnClasspath(flyway: Flyway): String =
            flyway.info().all().mapNotNull { it.version }.maxOf { it }.toString()
    }
}
