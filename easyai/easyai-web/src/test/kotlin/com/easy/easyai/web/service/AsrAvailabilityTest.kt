package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrAvailabilityTest {

    private val owners = listOf("alice")

    private fun asrRow(modelId: String, protocol: Protocol = Protocol.OPENAI) = ModelProviderConfig(
        id = "row-1",
        name = "ASR",
        protocol = protocol,
        isCustom = false,
        baseUrl = "https://ws-abc123.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey = "sk-test",
        modelId = modelId,
        modelType = ModelType.ASR
    )

    private val refineRow = ModelProviderConfig(
        id = "row-refine",
        name = "Refine",
        protocol = Protocol.OPENAI,
        isCustom = false,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "sk-test",
        modelId = "gpt-4o-mini"
    )

    private fun resolver(asr: ModelProviderConfig?, refine: ModelProviderConfig?): AuxModelResolver {
        val mock = mockk<AuxModelResolver>()
        coEvery { mock.resolveConfig(owners, AuxModelTask.ASR) } returns asr
        coEvery { mock.resolveConfig(owners, AuxModelTask.DICTATION_REFINE) } returns refine
        return mock
    }

    @Nested
    inner class Availability {

        @Test
        fun `without a configured row the mic is off and the reason names the missing task`() = runTest {
            val service = AsrService(resolver(null, null))
            val availability = service.availability(owners)
            assertFalse(availability.configured)
            assertEquals("NONE", availability.transport)
            assertEquals(AsrCapabilityResolver.REASON_NOT_CONFIGURED, availability.reason)
            assertFalse(availability.refineReady)
            assertNull(availability.modelId)
        }

        @Test
        fun `a realtime row reports the websocket transport and the polish availability`() = runTest {
            val service = AsrService(resolver(asrRow("qwen-audio-3.1-asr-flash-streaming"), refineRow))
            val availability = service.availability(owners)
            assertTrue(availability.configured)
            assertEquals("WEBSOCKET", availability.transport)
            assertEquals(AsrCapabilityResolver.CAPABILITY_REALTIME_STREAMING, availability.capability)
            assertNull(availability.reason)
            assertTrue(availability.refineReady)
            assertEquals("qwen-audio-3.1-asr-flash-streaming", availability.modelId)
        }

        @Test
        fun `a legacy row reports the HTTP transport without a second lookup`() = runTest {
            val service = AsrService(resolver(asrRow("whisper-1"), null))
            val availability = service.availability(owners)
            assertEquals("HTTP", availability.transport)
            assertEquals(AsrCapabilityResolver.CAPABILITY_HTTP_SEGMENT, availability.capability)
            assertFalse(availability.refineReady)
        }

        @Test
        fun `a configured row that cannot take a microphone is still configured`() = runTest {
            val service = AsrService(resolver(asrRow("qwen-audio-3.1-asr-flash-filetrans"), null))
            val availability = service.availability(owners)
            assertTrue(availability.configured)
            assertEquals("NONE", availability.transport)
            assertEquals(AsrCapabilityResolver.REASON_FILE_ONLY_MODEL, availability.reason)
        }
    }
}
