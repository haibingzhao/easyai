package com.easy.easyai.tools.web

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests [IntegrationConfig]'s deployment-wide STATIC override: when installed it wins over the file
 * for both [IntegrationConfig.load] and the `resolve*` key resolution, and clearing it hands control
 * back to the file / environment chain.
 */
class IntegrationConfigTest {

    @AfterEach
    fun tearDown() {
        // The STATIC override is process-global; always clear it so it cannot leak into other tests.
        IntegrationConfig.setStaticOverride(null)
    }

    @Test
    fun `missing file yields null and no static override`(@TempDir dir: Path) {
        assertNull(IntegrationConfig.load(dir.resolve("integrations.json")))
        assertFalse(IntegrationConfig.isStaticOverridden())
    }

    @Test
    fun `a static override wins over the file`(@TempDir dir: Path) {
        val file = dir.resolve("integrations.json")
        IntegrationConfig.save(IntegrationConfig(exaApiKey = "from-file"), file)
        assertEquals("from-file", IntegrationConfig.load(file)?.exaApiKey)

        IntegrationConfig.setStaticOverride(IntegrationConfig(exaApiKey = "pinned", websearchProvider = "parallel"))
        assertTrue(IntegrationConfig.isStaticOverridden())
        assertEquals("pinned", IntegrationConfig.load(file)?.exaApiKey)
        // resolve* reads through load(), so the pinned key is what tools actually build with.
        assertEquals("pinned", IntegrationConfig.resolveExaApiKey())
        assertEquals("parallel", IntegrationConfig.resolveWebsearchProvider())

        IntegrationConfig.setStaticOverride(null)
        assertFalse(IntegrationConfig.isStaticOverridden())
        assertEquals("from-file", IntegrationConfig.load(file)?.exaApiKey)
    }
}
