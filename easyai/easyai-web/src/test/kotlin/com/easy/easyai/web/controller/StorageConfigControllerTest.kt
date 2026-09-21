package com.easy.easyai.web.controller

import com.easy.easyai.core.storage.StorageSettings
import com.easy.easyai.core.storage.StorageSettingsResult
import com.easy.easyai.core.storage.StorageSettingsService
import com.easy.easyai.core.storage.StorageSource
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [StorageConfigController] — the HTTP surface of the per-user storage settings.
 *
 * The credential contract outranks the happy path here: the read path may only ever expose a
 * mask, and every degradation (no persistence at all, a store that vanished underneath) has to
 * answer 503 rather than pretend the configuration was accepted.
 */
class StorageConfigControllerTest {

    private val service = mockk<StorageSettingsService>()

    private fun controller(): StorageConfigController = StorageConfigController(service)

    private fun stored() = StorageSettings(
        enabled = true, type = "aliyun",
        endpoint = "https://oss-cn-hangzhou.aliyuncs.com", bucket = "easyai-market",
        accessKeyId = "LTAI5tExample", accessKeySecret = "sk-secret-12345678"
    )

    private fun request(overrides: Map<String, Any?> = emptyMap()) = SaveStorageConfigRequest(
        enabled = overrides["enabled"] as? Boolean ?: true,
        type = overrides["type"] as? String ?: "local",
        endpoint = overrides["endpoint"] as? String,
        bucket = overrides["bucket"] as? String,
        accessKeyId = overrides["accessKeyId"] as? String,
        accessKeySecret = overrides["accessKeySecret"] as? String,
        localDir = overrides["localDir"] as? String
    )

    private fun statusOf(block: () -> Any?): HttpStatusCode =
        assertFailsWith<ResponseStatusException> { block() }.statusCode

    @Nested
    inner class `reading the configuration` {

        @Test
        fun `the stored secret is only ever shown masked`() {
            coEvery { service.current(any()) } returns stored()
            coEvery { service.effectiveSource(any()) } returns StorageSource.USER

            val dto = controller().getConfig().block()!!

            assertEquals("sk-s****5678", dto.accessKeySecret)
            assertEquals("user", dto.effectiveSource, "the frontend badge needs the layer in force, lowercased")
        }

        @Test
        fun `nothing saved yet opens on the disabled defaults`() {
            coEvery { service.current(any()) } returns null
            coEvery { service.effectiveSource(any()) } returns StorageSource.NONE

            val dto = controller().getConfig().block()!!

            assertFalse(dto.enabled)
            assertNull(dto.accessKeySecret)
            assertEquals(StorageSettings.TYPE_ALIYUN, dto.type)
            assertEquals("none", dto.effectiveSource)
        }

        @Test
        fun `a short secret is fully masked`() {
            coEvery { service.current(any()) } returns stored().copy(accessKeySecret = "tiny")
            coEvery { service.effectiveSource(any()) } returns StorageSource.NONE

            val dto = controller().getConfig().block()!!

            assertEquals("****", dto.accessKeySecret)
        }
    }

    @Nested
    inner class `saving` {

        @Test
        fun `an invalid draft answers 400 with the factory complaint`() {
            coEvery { service.save(any(), any()) } returns StorageSettingsResult.Invalid("storage endpoint and bucket are required when type=aliyun")

            val error = assertFailsWith<ResponseStatusException> { controller().saveConfig(request()).block() }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            assertTrue("endpoint" in error.reason!!, "got: ${error.reason}")
        }

        @Test
        fun `a missing store answers 503`() {
            coEvery { service.save(any(), any()) } returns StorageSettingsResult.Unavailable

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf { controller().saveConfig(request()).block() })
        }

        @Test
        fun `a blank secret is forwarded as blank so the service can keep the stored one`() {
            val captured = slot<StorageSettings>()
            coEvery { service.save(any(), capture(captured)) } returns StorageSettingsResult.Saved(stored())
            coEvery { service.effectiveSource(any()) } returns StorageSource.NONE

            controller().saveConfig(request(mapOf("accessKeySecret" to null))).block()

            assertEquals("", captured.captured.accessKeySecret, "blank means unchanged, and that merge lives in the service")
        }

        @Test
        fun `absent fields fall back to the disabled defaults`() {
            val captured = slot<StorageSettings>()
            coEvery { service.save(any(), capture(captured)) } returns StorageSettingsResult.Saved(stored())
            coEvery { service.effectiveSource(any()) } returns StorageSource.NONE

            controller().saveConfig(SaveStorageConfigRequest()).block()

            assertFalse(captured.captured.enabled)
            assertEquals(StorageSettings.TYPE_ALIYUN, captured.captured.type)
        }
    }

    @Nested
    inner class `probing` {

        @Test
        fun `a clean round-trip reports success`() {
            coEvery { service.probe(any(), any()) } returns null

            val result = controller().testConfig(request()).block()!!

            assertTrue(result.success)
        }

        @Test
        fun `a failed probe reports the reason instead of swallowing it`() {
            coEvery { service.probe(any(), any()) } returns "AccessDenied"

            val result = controller().testConfig(request()).block()!!

            assertFalse(result.success)
            assertEquals("AccessDenied", result.message)
        }
    }

    @Nested
    inner class `without persistence nothing is configurable` {

        private val bare = StorageConfigController()

        @Test
        fun `reading is unavailable`() {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf { bare.getConfig().block() })
        }

        @Test
        fun `saving is unavailable`() {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf { bare.saveConfig(request()).block() })
        }

        @Test
        fun `probing is unavailable`() {
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, statusOf { bare.testConfig(request()).block() })
        }
    }
}
