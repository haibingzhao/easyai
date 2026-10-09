package com.easy.easyai.web.controller

import com.easy.easyai.tools.web.IntegrationConfig
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Unit tests for [IntegrationController]'s deployment-wide STATIC gate: when `easyai.integrations.*`
 * pins the keys, the status reports `source=static` and the write endpoint refuses with 403.
 *
 * Redirects `user.home` to a temp directory so `~/.easyai/integrations.json` is not touched.
 */
class IntegrationControllerTest {

    private lateinit var tempHome: java.nio.file.Path
    private lateinit var previousHome: String
    private val controller = IntegrationController()

    @BeforeEach
    fun setUp() {
        tempHome = Files.createTempDirectory("easyai-integration-controller-test")
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.toString())
    }

    @AfterEach
    fun tearDown() {
        // The STATIC override is process-global; always clear it so it cannot leak into other tests.
        IntegrationConfig.setStaticOverride(null)
        System.setProperty("user.home", previousHome)
        tempHome.toFile().deleteRecursively()
    }

    @Test
    fun `file-driven status reports source file`() {
        val status = controller.getStatus().block()!!

        assertEquals("file", status["source"])
    }

    @Test
    fun `static layer reports source static and never echoes keys`() {
        IntegrationConfig.setStaticOverride(IntegrationConfig(exaApiKey = "exa-secret-key-1234", websearchProvider = "exa"))

        val status = controller.getStatus().block()!!

        assertEquals("static", status["source"])
        @Suppress("UNCHECKED_CAST")
        val webSearch = status["webSearch"] as Map<String, Any?>
        // A member sees that search is configured, but never a slice of the deployment-pinned secret.
        assertEquals(true, webSearch["exaConfigured"])
        assertEquals(true, webSearch["configured"])
        assertNull(webSearch["exaApiKey"])
        assertNull(webSearch["parallelApiKey"])
    }

    @Test
    fun `static layer refuses writes with 403`() {
        IntegrationConfig.setStaticOverride(IntegrationConfig(exaApiKey = "exa-secret-key-1234"))

        val ex = assertFailsWith<ResponseStatusException> {
            controller.updateSettings(IntegrationUpdateRequest(exaApiKey = "attacker")).block()
        }

        assertEquals(403, ex.statusCode.value())
    }
}
