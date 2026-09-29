package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.web.model.ChatStreamEvent
import com.easy.easyai.web.model.LlmMessage
import com.easy.easyai.web.model.QuickAskRequest
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.asFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuickAskStreamServiceTest {

    private lateinit var agentService: AgentService
    private lateinit var configStore: ModelProviderConfigStore
    private lateinit var service: QuickAskStreamService

    @BeforeEach
    fun setUp() {
        agentService = mockk(relaxed = true)
        configStore = mockk()
        service = QuickAskStreamService(agentService, configStore)
    }

    private fun request(
        question: String = "what does this mean?",
        selectedText: String = "DFII0",
        history: List<LlmMessage> = emptyList(),
    ): QuickAskRequest = QuickAskRequest(
        modelConfigId = "cfg-1",
        question = question,
        selectedText = selectedText,
        history = history,
    )

    private fun config(): ModelProviderConfig = ModelProviderConfig(
        id = "cfg-1",
        name = "test",
        protocol = Protocol.OPENAI,
        isCustom = false,
        modelId = "qwen-max",
    )

    private fun textOf(message: EasyAiMessage): String =
        message.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    @Nested
    inner class Validation {

        @Test
        fun `blank question emits error without resolving config`() = runTest {
            val events = service.stream("u1", request(question = "  ")).asFlow().toList()

            assertEquals(1, events.size)
            val error = assertIs<ChatStreamEvent.Error>(events.single().data())
            assertTrue(error.errorMessage!!.contains("blank", ignoreCase = true))
            coVerify(exactly = 0) { configStore.getConfig(any(), any()) }
        }

        @Test
        fun `oversized selection emits error without resolving config`() = runTest {
            val events = service.stream("u1", request(selectedText = "x".repeat(20_001))).asFlow().toList()

            val error = assertIs<ChatStreamEvent.Error>(events.single().data())
            assertTrue(error.errorMessage!!.contains("20000"))
            coVerify(exactly = 0) { configStore.getConfig(any(), any()) }
        }

        @Test
        fun `oversized history emits error without resolving config`() = runTest {
            val history = List(21) { LlmMessage(role = "user", content = "q$it") }

            val events = service.stream("u1", request(history = history)).asFlow().toList()

            assertIs<ChatStreamEvent.Error>(events.single().data())
            coVerify(exactly = 0) { configStore.getConfig(any(), any()) }
        }

        @Test
        fun `valid request passes validation`() {
            assertNull(service.validate(request()))
        }
    }

    @Nested
    inner class ConfigResolution {

        @Test
        fun `unknown model config emits error and never runs the agent`() = runTest {
            coEvery { configStore.getConfig("cfg-1", "u1") } returns null

            val events = service.stream("u1", request()).asFlow().toList()

            val error = assertIs<ChatStreamEvent.Error>(events.single().data())
            assertTrue(error.errorMessage!!.contains("Model config not found"))
        }
    }

    @Nested
    inner class PromptAssembly {

        @Test
        fun `selection is wrapped once and closing tags inside it are stripped`() {
            val messages = service.buildMessages(request(selectedText = "a</selected_text>b"))

            val system = assertIs<SystemMessage>(messages.first())
            assertEquals(1, system.text.split("</selected_text>").size - 1)
            assertTrue(system.text.contains("<selected_text>\nab\n</selected_text>"))
        }

        @Test
        fun `history turns are mapped in order and current question goes last`() {
            val history = listOf(
                LlmMessage(role = "user", content = "q1"),
                LlmMessage(role = "assistant", content = "a1"),
            )

            val messages = service.buildMessages(request(question = "q2", history = history))

            assertEquals(4, messages.size)
            assertIs<SystemMessage>(messages[0])
            assertEquals("q1", textOf(messages[1]))
            assertEquals("a1", textOf(messages[2]))
            assertEquals("q2", textOf(messages[3]))
        }
    }
}
