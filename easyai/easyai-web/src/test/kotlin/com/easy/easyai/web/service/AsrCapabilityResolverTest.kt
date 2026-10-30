package com.easy.easyai.web.service

import com.easy.easyai.api.model.AsrCapabilities
import com.easy.easyai.api.model.AsrParamSpec
import com.easy.easyai.api.model.ModelCatalogEntry
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.api.model.SpeechCapabilities
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AsrCapabilityResolverTest {

    private val resolver = AsrCapabilityResolver(catalog = null)

    private fun config(
        modelId: String,
        protocol: Protocol = Protocol.OPENAI,
        modelType: ModelType = ModelType.ASR,
        baseUrl: String = STREAMING_HOST,
        apiKey: String = "sk-test",
        enabled: Boolean = true,
        options: String? = null
    ) = ModelProviderConfig(
        id = "row-1",
        name = "ASR row",
        protocol = protocol,
        isCustom = false,
        baseUrl = baseUrl,
        apiKey = apiKey,
        modelId = modelId,
        enabled = enabled,
        modelType = modelType,
        mediaOptions = options
    )

    @Nested
    inner class Configuration {

        @Test
        fun `no configured row is reported as unconfigured`() = runTest {
            val route = resolver.resolve(null)
            assertEquals(AsrTransport.NONE, route.transport)
            assertEquals(AsrCapabilityResolver.REASON_NOT_CONFIGURED, route.reason)
            assertTrue(!route.usable)
        }

        @Test
        fun `a row without an api key is incomplete rather than unusable by model`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-streaming", apiKey = ""))
            assertEquals(AsrCapabilityResolver.REASON_INCOMPLETE_CONFIG, route.reason)
            assertEquals("qwen-audio-3.1-asr-flash-streaming", route.modelId)
        }

        @Test
        fun `a row that is not an ASR row is rejected`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-streaming", modelType = ModelType.CHAT))
            assertEquals(AsrCapabilityResolver.REASON_UNSUPPORTED_MODEL, route.reason)
        }

        @Test
        fun `a disabled row is reported as disabled`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-streaming", enabled = false))
            assertEquals(AsrCapabilityResolver.REASON_DISABLED, route.reason)
        }
    }

    @Nested
    inner class TransportRouting {

        @Test
        fun `both streaming models route to the websocket transport`() = runTest {
            for (model in listOf("qwen-audio-3.0-asr-flash-streaming", "qwen-audio-3.1-asr-flash-streaming")) {
                val route = resolver.resolve(config(model))
                assertEquals(AsrTransport.WEBSOCKET, route.transport, model)
                assertEquals(AsrCapabilityResolver.CAPABILITY_REALTIME_STREAMING, route.capability, model)
                assertEquals(AsrCapabilityResolver.SOURCE_BUILTIN, route.capabilitySource, model)
                assertNull(route.reason, model)
            }
        }

        @Test
        fun `the message model is labelled as its own capability`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-message"))
            assertEquals(AsrTransport.WEBSOCKET, route.transport)
            assertEquals(AsrCapabilityResolver.CAPABILITY_REALTIME_MESSAGE, route.capability)
        }

        @Test
        fun `file-only models are refused with a model hint`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-filetrans"))
            assertEquals(AsrTransport.NONE, route.transport)
            assertEquals(AsrCapabilityResolver.REASON_FILE_ONLY_MODEL, route.reason)
            assertEquals(AsrCapabilityResolver.HINT_SELECT_REALTIME_MODEL, route.hint)
        }

        @Test
        fun `an unlisted realtime family id is never guessed onto HTTP`() = runTest {
            for (model in listOf("qwen-audio-2.0-asr-realtime", "qwen-audio-9-asr-flash-streaming")) {
                val route = resolver.resolve(config(model))
                assertEquals(AsrTransport.NONE, route.transport, model)
                assertEquals(AsrCapabilityResolver.REASON_UNSUPPORTED_REALTIME_VARIANT, route.reason, model)
            }
        }

        @Test
        fun `a legacy compatible transcription row keeps the HTTP transport`() = runTest {
            val route = resolver.resolve(config("whisper-1", baseUrl = "https://api.openai.com/v1"))
            assertEquals(AsrTransport.HTTP, route.transport)
            assertEquals(AsrCapabilityResolver.CAPABILITY_HTTP_SEGMENT, route.capability)
            assertNull(route.reason)
        }

        @Test
        fun `a protocol the HTTP transcription cannot speak is refused`() = runTest {
            val route = resolver.resolve(config("whisper-1", protocol = Protocol.ANTHROPIC))
            assertEquals(AsrCapabilityResolver.REASON_UNSUPPORTED_PROVIDER, route.reason)
        }

        @Test
        fun `the no-suffix flash models stay gated until the multipart probe passes`() = runTest {
            for (model in listOf("qwen-audio-3.0-asr-flash", "qwen-audio-3.1-asr-flash")) {
                val route = resolver.resolve(config(model))
                assertEquals(AsrTransport.NONE, route.transport, model)
                assertEquals(AsrCapabilityResolver.REASON_UNVERIFIED_ENDPOINT, route.reason, model)
            }
        }
    }

    @Nested
    inner class EndpointTrust {

        @Test
        fun `a streaming model on a look-alike host is refused`() = runTest {
            val lookAlikes = listOf(
                "https://maas.aliyuncs.com.evil.tld/api/v1",
                "https://user@evil.tld/maas.aliyuncs.com/api/v1",
                "https://evil.tld/api/v1?next=maas.aliyuncs.com",
                "https://evil.tld/api/v1#maas.aliyuncs.com"
            )
            for (baseUrl in lookAlikes) {
                val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-streaming", baseUrl = baseUrl))
                assertEquals(AsrCapabilityResolver.REASON_UNVERIFIED_ENDPOINT, route.reason, baseUrl)
            }
        }

        @Test
        fun `a bailian workspace host is trusted`() = runTest {
            for (baseUrl in listOf(
                "https://ws-abc123.cn-beijing.maas.aliyuncs.com/api/v1",
                "https://dashscope.aliyuncs.com/compatible-mode/v1"
            )) {
                assertEquals(AsrTransport.WEBSOCKET, resolver.resolve(config("qwen-audio-3.1-asr-flash-streaming", baseUrl = baseUrl)).transport, baseUrl)
            }
        }
    }

    @Nested
    inner class Parameters {

        @Test
        fun `3_0 sends only the silence threshold`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.0-asr-flash-streaming"))
            assertEquals(400, route.parameters["max_sentence_silence"])
            assertTrue(!route.parameters.containsKey("vad_model"))
            assertTrue(!route.parameters.containsKey("keep_dialect"))
        }

        @Test
        fun `3_1 sends the near-field vad and the project silence threshold`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-streaming"))
            assertEquals("near_meeting_16k", route.parameters["vad_model"])
            assertEquals(400, route.parameters["max_sentence_silence"])
        }

        @Test
        fun `the row's own values win over the capability defaults`() = runTest {
            val route = resolver.resolve(
                config(
                    "qwen-audio-3.1-asr-flash-streaming",
                    options = """{"vad_model":"far_field_meeting_16k","max_sentence_silence":900}"""
                )
            )
            assertEquals("far_field_meeting_16k", route.parameters["vad_model"])
            assertEquals(900, route.parameters["max_sentence_silence"])
        }

        @Test
        fun `the intermediate-result switch stays fixed even when the row disagrees`() = runTest {
            val route = resolver.resolve(
                config("qwen-audio-3.1-asr-flash-message", options = """{"intermediate_result_enabled":false}""")
            )
            assertEquals(AsrTransport.WEBSOCKET, route.transport)
            assertEquals(true, route.parameters["intermediate_result_enabled"])
        }

        @Test
        fun `an empty options object is usable`() = runTest {
            val route = resolver.resolve(config("qwen-audio-3.1-asr-flash-streaming", options = ""))
            assertEquals(AsrTransport.WEBSOCKET, route.transport)
            assertTrue(route.parameters.isNotEmpty())
        }

        @Test
        fun `parameters the model does not declare are rejected before any upstream call`() = runTest {
            // A list of pairs, not a map: most cases share a model id, and a map would keep only the
            // last value per key, silently leaving one case to run.
            val cases = listOf(
                "qwen-audio-3.0-asr-flash-streaming" to """{"vad_model":"near_meeting_16k"}""",
                "qwen-audio-3.1-asr-flash-message" to """{"multi_threshold_mode_enabled":true}""",
                "qwen-audio-3.1-asr-flash-streaming" to """{"max_sentence_silence":100}""",
                "qwen-audio-3.1-asr-flash-streaming" to """{"max_sentence_silence":7000}""",
                "qwen-audio-3.1-asr-flash-streaming" to """{"vad_model":"near"}""",
                "qwen-audio-3.1-asr-flash-streaming" to """{"keep_dialect":"yes"}""",
                "qwen-audio-3.1-asr-flash-streaming" to """{"language_hints":["zh","en","ja","ko","fr"]}""",
                "qwen-audio-3.1-asr-flash-streaming" to """{"max_sentence_silence":"""
            )
            for ((model, options) in cases) {
                val route = resolver.resolve(config(model, options = options))
                assertEquals(AsrCapabilityResolver.REASON_INVALID_MODEL_OPTIONS, route.reason, options)
                assertEquals(AsrTransport.NONE, route.transport, options)
            }
        }
    }

    @Nested
    inner class CatalogPrecedence {

        @Test
        fun `a catalog block answers before the built-in table`() = runTest {
            val catalog = mockk<ModelCatalogService>()
            coEvery { catalog.lookup("qwen-audio-3.1-asr-flash-streaming") } returns entry(
                AsrCapabilities(
                    channel = AsrCapabilities.CHANNEL_WEBSOCKET,
                    params = mapOf(
                        "custom_threshold" to AsrParamSpec(type = AsrParamSpec.TYPE_INT, min = 0, max = 10, default = 5)
                    )
                )
            )
            val route = AsrCapabilityResolver(catalog).resolve(config("qwen-audio-3.1-asr-flash-streaming"))
            assertEquals(AsrCapabilityResolver.SOURCE_CATALOG, route.capabilitySource)
            assertEquals(AsrTransport.WEBSOCKET, route.transport)
            assertEquals(5, route.parameters["custom_threshold"])
            assertTrue(!route.parameters.containsKey("vad_model"))
        }

        @Test
        fun `a catalog row without an asr block still falls back to the built-in table`() = runTest {
            val catalog = mockk<ModelCatalogService>()
            coEvery { catalog.lookup(any()) } returns ModelCatalogEntry(
                modelId = "qwen-audio-3.1-asr-flash-streaming",
                vendor = "bailian",
                displayName = "streaming",
                source = "bailian-refresh"
            )
            val route = AsrCapabilityResolver(catalog).resolve(config("qwen-audio-3.1-asr-flash-streaming"))
            assertEquals(AsrCapabilityResolver.SOURCE_BUILTIN, route.capabilitySource)
            assertEquals(AsrTransport.WEBSOCKET, route.transport)
        }
    }

    private fun entry(asr: AsrCapabilities) = ModelCatalogEntry(
        modelId = "qwen-audio-3.1-asr-flash-streaming",
        vendor = "bailian",
        displayName = "streaming",
        speechCapabilities = SpeechCapabilities(asr = asr),
        source = "bailian-asr-doc"
    )

    private companion object {
        const val STREAMING_HOST = "https://ws-abc123.cn-beijing.maas.aliyuncs.com/api/v1"
    }
}
