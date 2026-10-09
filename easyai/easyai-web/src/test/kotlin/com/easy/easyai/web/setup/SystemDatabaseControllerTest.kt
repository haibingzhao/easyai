package com.easy.easyai.web.setup

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Unit tests for [SystemDatabaseController]'s deployment-wide gate: when the database is pinned by
 * Spring properties (`easyai.database.source=spring`, the B/S multi-tenant mode) the runtime endpoints
 * are read-only — `apply`/`test` refuse with 403 so a member cannot write a `db-config.json` that would
 * override the deployment configuration on the next restart.
 *
 * Redirects `user.home` to a temp directory so `~/.easyai/db-config.json` is not touched.
 */
class SystemDatabaseControllerTest {

    private lateinit var tempHome: java.nio.file.Path
    private lateinit var previousHome: String

    @BeforeEach
    fun setUp() {
        tempHome = Files.createTempDirectory("easyai-system-database-test")
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.toString())
    }

    @AfterEach
    fun tearDown() {
        System.setProperty("user.home", previousHome)
        tempHome.toFile().deleteRecursively()
    }

    @Test
    fun `spring-pinned database reports its source`() {
        val controller = SystemDatabaseController(databaseSource = "spring")

        val info = controller.getDatabaseInfo().block()!!

        assertEquals("spring", info["source"])
        assertEquals(true, info["configured"])
    }

    @Test
    fun `spring-pinned database refuses apply with 403`() {
        val controller = SystemDatabaseController(databaseSource = "spring")

        val ex = assertFailsWith<ResponseStatusException> {
            controller.apply(DatabaseSetupRequest(dbType = "h2")).block()
        }

        assertEquals(403, ex.statusCode.value())
    }

    @Test
    fun `spring-pinned database refuses test with 403`() {
        val controller = SystemDatabaseController(databaseSource = "spring")

        val ex = assertFailsWith<ResponseStatusException> {
            controller.testConnection(DatabaseSetupRequest(dbType = "h2")).block()
        }

        assertEquals(403, ex.statusCode.value())
    }

    @Test
    fun `file-driven database stays editable`() {
        val controller = SystemDatabaseController(databaseSource = "file")

        val result = controller.apply(DatabaseSetupRequest(dbType = "h2", h2Dir = tempHome.resolve("db").toString())).block()!!

        assertEquals(true, result["success"])
        assertEquals("file", controller.getDatabaseInfo().block()!!["source"])
    }
}
