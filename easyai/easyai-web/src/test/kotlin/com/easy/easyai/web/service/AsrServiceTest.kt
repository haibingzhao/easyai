package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.model.aux.ResolvedAuxModel
import com.easy.easyai.tools.media.OpenAiCompatibleClient
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class AsrServiceTest {

    private val sampleConfig = ModelProviderConfig(
        id = "cfg-asr",
        name = "Whisper",
        protocol = Protocol.OPENAI,
        isCustom = false,
        baseUrl = "https://api.openai.com/v1",
        apiKey = "sk-test",
        modelId = "whisper-1"
    )

    @AfterEach
    fun tearDown() = unmockkAll()

    @Nested
    inner class Transcribe {

        @Test
        fun `rejects an empty segment with 400`() = runTest {
            val service = AsrService(mockk(relaxed = true))
            val e = assertFailsWith<ResponseStatusException> { service.transcribe("alice", ByteArray(0), null) }
            assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
        }

        @Test
        fun `rejects an oversized segment with 413`() = runTest {
            val service = AsrService(mockk(relaxed = true))
            val e = assertFailsWith<ResponseStatusException> {
                service.transcribe("alice", ByteArray(2 * 1024 * 1024 + 1), null)
            }
            assertEquals(HttpStatus.PAYLOAD_TOO_LARGE, e.statusCode)
        }

        @Test
        fun `answers 409 when the asr task is unconfigured`() = runTest {
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolveConfig("alice", AuxModelTask.ASR) } returns null
            val e = assertFailsWith<ResponseStatusException> {
                AsrService(resolver).transcribe("alice", byteArrayOf(1, 2, 3), null)
            }
            assertEquals(HttpStatus.CONFLICT, e.statusCode)
        }

        @Test
        fun `answers 409 when persistence is absent`() = runTest {
            val e = assertFailsWith<ResponseStatusException> {
                AsrService(null).transcribe("alice", byteArrayOf(1, 2, 3), null)
            }
            assertEquals(HttpStatus.CONFLICT, e.statusCode)
        }

        @Test
        fun `transcribes through the configured row`() = runTest {
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolveConfig("alice", AuxModelTask.ASR) } returns sampleConfig
            mockkConstructor(OpenAiCompatibleClient::class)
            coEvery { anyConstructed<OpenAiCompatibleClient>().transcribe(any(), any(), any()) } returns "你好世界"
            assertEquals("你好世界", AsrService(resolver).transcribe("alice", byteArrayOf(1, 2, 3), "zh"))
        }

        @Test
        fun `maps provider errors onto 502`() = runTest {
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolveConfig("alice", AuxModelTask.ASR) } returns sampleConfig
            mockkConstructor(OpenAiCompatibleClient::class)
            coEvery { anyConstructed<OpenAiCompatibleClient>().transcribe(any(), any(), any()) } throws
                IllegalStateException("provider exploded")
            val e = assertFailsWith<ResponseStatusException> {
                AsrService(resolver).transcribe("alice", byteArrayOf(1, 2, 3), null)
            }
            assertEquals(HttpStatus.BAD_GATEWAY, e.statusCode)
        }
    }

    @Nested
    inner class Refine {

        @Test
        fun `rejects blank text with 400`() = runTest {
            val e = assertFailsWith<ResponseStatusException> {
                AsrService(mockk(relaxed = true)).refine("alice", "   ", "", "")
            }
            assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
        }

        @Test
        fun `answers 409 when the refine task is unconfigured`() = runTest {
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolve("alice", AuxModelTask.DICTATION_REFINE) } returns null
            val e = assertFailsWith<ResponseStatusException> {
                AsrService(resolver).refine("alice", "你好", "前文", "")
            }
            assertEquals(HttpStatus.CONFLICT, e.statusCode)
        }

        @Test
        fun `returns the model output trimmed and fence-stripped`() = runBlocking {
            val chatModel = mockk<ChatModel>()
            val assistant = mockk<AssistantMessage> {
                every { text } returns "```text\n你好，世界。\n```"
            }
            val generation = mockk<Generation> { every { output } returns assistant }
            val response = mockk<ChatResponse> { every { result } returns generation }
            coEvery { chatModel.call(any<Prompt>()) } returns response
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolve("alice", AuxModelTask.DICTATION_REFINE) } returns
                ResolvedAuxModel(chatModel, sampleConfig)
            val refined = AsrService(resolver).refine("alice", "你好世界", "前文", "后文")
            assertEquals("你好，世界。", refined)
        }

        // runBlocking, not runTest: refine() guards the model call with withTimeout over Dispatchers.IO,
        // and runTest's virtual clock can leap straight to that deadline while the real IO dispatch is still
        // warming up (slow mock generation on a cold JVM), turning this into a spurious 504.
        @Test
        fun `answers 502 when the model call fails`() = runBlocking {
            val chatModel = mockk<ChatModel>()
            coEvery { chatModel.call(any<Prompt>()) } throws
                IllegalStateException("boom")
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolve("alice", AuxModelTask.DICTATION_REFINE) } returns
                ResolvedAuxModel(chatModel, sampleConfig)
            val e = assertFailsWith<ResponseStatusException> {
                AsrService(resolver).refine("alice", "你好", "", "")
            }
            assertEquals(HttpStatus.BAD_GATEWAY, e.statusCode)
        }

        @Test
        fun `sends refine with factory options and thinking forced off`() = runBlocking {
            val chatModel = mockk<ChatModel>()
            val assistant = mockk<AssistantMessage> { every { text } returns "你好，世界。" }
            val generation = mockk<Generation> { every { output } returns assistant }
            val response = mockk<ChatResponse> { every { result } returns generation }
            val prompts = slot<Prompt>()
            coEvery { chatModel.call(capture(prompts)) } returns response
            val resolver = mockk<AuxModelResolver>()
            coEvery { resolver.resolve("alice", AuxModelTask.DICTATION_REFINE) } returns
                ResolvedAuxModel(chatModel, sampleConfig.copy(options = ModelOptions(thinking = true)))
            val factory = mockk<ChatModelFactory>()
            every { factory.supports(Protocol.OPENAI) } returns true
            val options = mockk<ChatOptions>()
            val configs = slot<ModelProviderConfig>()
            every { factory.build(capture(configs), emptyList()) } returns options
            val refined = AsrService(resolver, listOf(factory)).refine("alice", "你好世界", "", "")
            assertEquals("你好，世界。", refined)
            assertEquals(false, configs.captured.options?.thinking)
            assertSame(options, prompts.captured.options)
        }
    }
}
