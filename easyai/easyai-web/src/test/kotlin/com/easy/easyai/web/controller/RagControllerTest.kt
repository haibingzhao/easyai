package com.easy.easyai.web.controller

import com.easy.easyai.rag.RagConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit tests for [RagController]: partial update semantics, password masking,
 * and empty-string clearing of optional fields.
 *
 * Redirects `user.home` to a temp directory so `~/.easyai/rag.json` is not touched.
 */
class RagControllerTest {

    private lateinit var tempHome: java.nio.file.Path
    private lateinit var previousHome: String
    private val controller = RagController()

    @BeforeEach
    fun setUp() {
        tempHome = Files.createTempDirectory("easyai-rag-controller-test")
        previousHome = System.getProperty("user.home")
        System.setProperty("user.home", tempHome.toString())
    }

    @AfterEach
    fun tearDown() {
        System.setProperty("user.home", previousHome)
        tempHome.toFile().deleteRecursively()
    }

    private fun saveConfig(config: RagConfig) = runBlocking { RagConfig.save(config) }

    @Test
    fun `status masks stored password`() {
        saveConfig(RagConfig(enabled = false, password = "supersecretpw"))

        val status = controller.getStatus().block()!!

        assertEquals("supe****etpw", status["password"])
        assertEquals(false, status["enabled"])
        // Disabled config skips the health probe, so connected is deterministically false
        // (an enabled config would probe the real EasyRAG at the default localhost:8020)
        assertEquals(false, status["connected"])
    }

    @Test
    fun `null fields keep existing values`() {
        saveConfig(RagConfig(enabled = true, baseUrl = "http://rag:9000", username = "alice", password = "pw123456"))

        val result = controller.updateSettings(RagUpdateRequest(topK = 7)).block()!!

        assertEquals(true, result["success"])
        val config = runBlocking { RagConfig.load() }
        assertEquals("http://rag:9000", config.baseUrl)
        assertEquals("alice", config.username)
        assertEquals("pw123456", config.password)
        assertEquals(7, config.topK)
    }

    @Test
    fun `masked password echo keeps stored password`() {
        saveConfig(RagConfig(password = "supersecretpw"))

        controller.updateSettings(RagUpdateRequest(password = "supe****etpw")).block()

        val config = runBlocking { RagConfig.load() }
        assertEquals("supersecretpw", config.password)
    }

    @Test
    fun `new password replaces stored one`() {
        saveConfig(RagConfig(password = "old-password"))

        controller.updateSettings(RagUpdateRequest(password = "new-password")).block()

        val config = runBlocking { RagConfig.load() }
        assertEquals("new-password", config.password)
    }

    @Test
    fun `empty password clears it`() {
        saveConfig(RagConfig(password = "old-password"))

        controller.updateSettings(RagUpdateRequest(password = "")).block()

        val config = runBlocking { RagConfig.load() }
        assertNull(config.password)
    }

    @Test
    fun `empty username and workspace clear them`() {
        saveConfig(RagConfig(username = "alice", workspace = "ws-1"))

        controller.updateSettings(RagUpdateRequest(username = "", workspace = "")).block()

        val config = runBlocking { RagConfig.load() }
        assertNull(config.username)
        assertNull(config.workspace)
    }

    @Test
    fun `blank string is treated as clearing`() {
        saveConfig(RagConfig(username = "alice"))

        controller.updateSettings(RagUpdateRequest(username = "   ")).block()

        val config = runBlocking { RagConfig.load() }
        assertNull(config.username)
    }

    @Test
    fun `enabled toggle is persisted`() {
        saveConfig(RagConfig(enabled = false))

        controller.updateSettings(RagUpdateRequest(enabled = true)).block()

        assertTrue(runBlocking { RagConfig.load() }.enabled)

        controller.updateSettings(RagUpdateRequest(enabled = false)).block()

        assertFalse(runBlocking { RagConfig.load() }.enabled)
    }
}
