package com.easy.easyai.speech

import com.easy.easyai.core.media.MediaProviderSettings
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The façade is what `easyai-web` and `easyai-tools` see, so its provider gate is asserted here:
 * an unsupported provider must be answerable *before* a paid synthesis is attempted.
 */
class SpeechSynthesizerTest {

    private val synthesizer = SpeechSynthesizer.create()

    private fun settings(providerType: String) = MediaProviderSettings(
        id = "row-1",
        enabled = true,
        serviceKind = MediaProviderSettings.SERVICE_KIND_SPEECH,
        providerType = providerType,
        baseUrl = "https://ws-123.cn-beijing.maas.aliyuncs.com/api/v1",
        apiKey = "sk-test",
        defaultModel = "qwen-audio-3.1-tts-flash"
    )

    @Nested
    inner class ProviderGate {

        @Test
        fun `dashscope rows are supported regardless of case`() {
            assertTrue(synthesizer.supports("dashscope"))
            assertTrue(synthesizer.supports("DashScope"))
        }

        @Test
        fun `an openai-compatible speech row is not supported yet`() {
            assertFalse(synthesizer.supports(MediaProviderSettings.PROVIDER_OPENAI))
        }

        @Test
        fun `qwen tts in a bailian openai-compatible group uses the native socket`() {
            val row = settings(MediaProviderSettings.PROVIDER_OPENAI).copy(
                baseUrl = "https://llm-xy.cn-beijing.maas.aliyuncs.com/compatible-mode/v1"
            )
            assertTrue(synthesizer.supports(row))
        }

        @Test
        fun `qwen model id alone does not reroute a non-bailian openai provider`() {
            val row = settings(MediaProviderSettings.PROVIDER_OPENAI).copy(
                baseUrl = "https://api.openai.com/v1"
            )
            assertFalse(synthesizer.supports(row))
        }

        @Test
        fun `synthesizing through an unsupported provider fails without a request`() = runTest {
            val row = settings(MediaProviderSettings.PROVIDER_OPENAI).copy(
                baseUrl = "https://api.openai.com/v1",
                defaultModel = "tts-1"
            )
            val error = assertFailsWith<IllegalArgumentException> {
                synthesizer.synthesize(row, "你好")
            }
            assertTrue(error.message!!.contains("not supported"))
        }
    }

    @Nested
    inner class AudioCarrier {

        @Test
        fun `audio carries its own media type`() {
            val audio = SpeechAudio(byteArrayOf(1, 2, 3), "audio/mpeg")
            assertEquals("audio/mpeg", audio.mimeType)
            assertEquals(3, audio.bytes.size)
        }
    }
}
