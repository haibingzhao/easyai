package com.easy.easyai.autoconfigure.r2dbc

import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment
import java.nio.file.Files
import kotlin.test.assertEquals

/**
 * Tests the `easyai.database.source` flag emitted by [DatabaseConfigEnvironmentPostProcessor]: a
 * database resolved from Spring properties (`easyai.r2dbc.url`) is marked `spring` (deployment-pinned,
 * read-only at runtime), while one resolved from `db-config.json` is marked `file` (editable).
 */
class DatabaseConfigEnvironmentPostProcessorTest {

    private lateinit var tempHome: java.nio.file.Path
    private lateinit var previousHome: String
    private val processor = DatabaseConfigEnvironmentPostProcessor()
    private val application = mockk<SpringApplication>(relaxed = true)

    @BeforeEach
    fun setUp() {
        tempHome = Files.createTempDirectory("easyai-db-postprocessor-test")
        previousHome = System.getProperty("user.home")
        // No db-config.json in the temp home, so file resolution misses and Spring properties win.
        System.setProperty("user.home", tempHome.toString())
    }

    @AfterEach
    fun tearDown() {
        System.setProperty("user.home", previousHome)
        tempHome.toFile().deleteRecursively()
    }

    @Test
    fun `a spring-property url is marked source spring`() {
        val env = StandardEnvironment()
        env.propertySources.addFirst(
            MapPropertySource(
                "test",
                mapOf("easyai.r2dbc.url" to "r2dbc:postgresql://localhost:5432/easyai")
            )
        )

        processor.postProcessEnvironment(env, application)

        assertEquals("spring", env.getProperty("easyai.database.source"))
        assertEquals("true", env.getProperty("easyai.database.configured"))
        assertEquals("postgres", env.getProperty("easyai.database.type"))
    }

    @Test
    fun `a db-config file is marked source file`() {
        val config = DatabaseConfig(dbType = "h2", h2 = DatabaseConfig.H2Config(dir = tempHome.resolve("db").toString()))
        DatabaseConfig.save(config)
        val env = StandardEnvironment()

        processor.postProcessEnvironment(env, application)

        assertEquals("file", env.getProperty("easyai.database.source"))
        assertEquals("true", env.getProperty("easyai.database.configured"))
    }
}
