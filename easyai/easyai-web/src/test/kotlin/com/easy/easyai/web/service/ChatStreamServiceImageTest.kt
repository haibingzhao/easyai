package com.easy.easyai.web.service

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.FileRefContent
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.storage.ObjectStorageException
import com.easy.easyai.core.storage.StoredFileReference
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.web.model.ChatAttachment
import com.easy.easyai.web.model.ChatRequest
import com.easy.easyai.web.model.ChatStreamEvent
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

internal class ChatStreamServiceImageTest {
    private val manager = mockk<SessionManager>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>()
    private val factory = mockk<ChatModelFactory>()
    private val session = mockk<ChatSession>(relaxed = true)
    private val execution = mockk<SessionExecutionService>(relaxed = true)
    private val files = mockk<FileStorageService>()
    private val stream = mockk<EventStream<AgentEvent, List<AssistantMessage>>>()
    private val saved = slot<List<EasyAiMessage>>()
    private val prompted = slot<List<EasyAiMessage>>()
    private val queued = slot<UserMessage>()
    private val config = ModelProviderConfig("model-1", "Test model", Protocol.OPENAI, false, modelId = "test-model")
    private val context = AgentContext("default-agent", modelConfig = config, sessionId = "session-1", userId = "alice")
    private val text = "Look at this"
    private val bytes = byteArrayOf(1, 2, 3, 4)
    private val attachment = ChatAttachment("shot.png", "image/png", data = Base64.getEncoder().encodeToString(bytes))
    private val reference = StoredFileReference.create("alice", "session-1", "png")
    private val expectedContent = listOf(
        TextContent(text), FileRefContent(reference, attachment.name, attachment.mimeType, displayOffset = text.length)
    )
    private val request = ChatRequest(
        sessionId = "session-1", message = text, modelProviderConfigId = config.id, attachments = listOf(attachment)
    )
    private val service = ChatStreamService(
        manager, configStore, listOf(factory), fileStorageService = files, executionService = execution
    )

    @BeforeEach
    fun setup() {
        coEvery { configStore.getConfig(config.id, "alice") } returns config
        every { factory.supports(config.protocol) } returns true
        coEvery { manager.getOrCreateSession(any(), config, factory) } returns session
        coEvery { manager.loadMessages("session-1") } returns emptyList()
        coEvery { manager.saveSessionMessages(context, capture(saved)) } returns Unit
        every { session.id } returns "session-1"
        every { session.agentContext } returns context
        every { session.promptWithHistory(capture(prompted)) } returns stream
        every { session.followUpWithId(capture(queued)) } returns "followUp-1"
        every { session.steerWithId(capture(queued)) } returns "steer-1"
        every { execution.getActiveSession("session-1") } returns session
        every { stream.asFlow() } returns flowOf(AgentEndEvent("session-1", "completed"))
        coEvery { stream.result() } returns emptyList()
        coEvery { files.saveImage(any(), any(), any(), any(), any()) } coAnswers {
            yield()
            reference
        }
    }

    @AfterEach
    fun cleanup() {
        service.destroy()
    }

    @Nested
    inner class StreamChat {
        @Test
        fun `uploads for alice and persists and prompts with the stable image reference`() = runTest {
            val events = service.streamChat(request, "alice").toList()
            val message = assertIs<UserMessage>(saved.captured.single())
            assertEquals(expectedContent, message.content)
            assertEquals(message, prompted.captured.single())
            assertEquals(message.id, assertIs<ChatStreamEvent.UserMessageAck>(events.first().data()).messageId)
            assertIs<ChatStreamEvent.Done>(events.last().data())
            coVerify(exactly = 1) {
                files.saveImage("session-1", match { it.contentEquals(bytes) }, "png", "alice", "image/png")
                manager.saveSessionMessages(context, any())
            }
            verify(exactly = 1) { session.promptWithHistory(any()) }
        }

        @Test
        fun `upload failure emits only an error without saving or starting the model`() = runTest {
            coEvery { files.saveImage(any(), any(), any(), any(), any()) } throws ObjectStorageException("upload failed")
            val event = service.streamChat(request, "alice").toList().single()
            assertEquals("error", event.event())
            assertEquals("upload failed", assertIs<ChatStreamEvent.Error>(event.data()).errorMessage)
            coVerify(exactly = 1) {
                files.saveImage("session-1", match { it.contentEquals(bytes) }, "png", "alice", "image/png")
            }
            coVerify(exactly = 0) { manager.saveSessionMessages(any(), any()) }
            verify(exactly = 0) {
                session.promptWithHistory(any())
                execution.beginExecution(any(), any(), any(), any())
                stream.asFlow()
            }
        }

        @Test
        fun `upload cancellation propagates without persisting or prompting`() = runTest {
            coEvery { files.saveImage(any(), any(), any(), any(), any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { service.streamChat(request, "alice") }
            coVerify(exactly = 0) { manager.saveSessionMessages(any(), any()) }
            verify(exactly = 0) { session.promptWithHistory(any()) }
        }

        @Test
        fun `preserves plain text without uploading`() = runTest {
            val plainText = "  Keep this text\nand whitespace  "
            val events = service.streamChat(request.copy(message = plainText, attachments = null), "alice").toList()
            assertEquals(listOf(TextContent(plainText)), saved.captured.single().content)
            assertEquals(saved.captured, prompted.captured)
            assertIs<ChatStreamEvent.Done>(events.last().data())
            coVerify(exactly = 0) { files.saveImage(any(), any(), any(), any(), any()) }
        }
    }

    @Nested
    inner class QueuedMessages {
        @Test
        fun `follow up and steer upload for alice and queue only the stable reference`() = runTest {
            for (type in listOf("followUp", "steer")) {
                val response = service.addQueuedMessage("session-1", "alice", text, type, listOf(attachment))
                assertEquals("$type-1", response.id)
                assertEquals(type, response.type)
                assertEquals(text, response.content)
                assertEquals(expectedContent, queued.captured.content)
            }
            coVerify(exactly = 2) {
                files.saveImage("session-1", match { it.contentEquals(bytes) }, "png", "alice", "image/png")
            }
            verify(exactly = 1) {
                session.followUpWithId(any<UserMessage>())
                session.steerWithId(any<UserMessage>())
            }
        }

        @Test
        fun `upload failure cannot save prompt or enqueue either queue type`() = runTest {
            val failure = ObjectStorageException("upload failed")
            coEvery { files.saveImage(any(), any(), any(), any(), any()) } throws failure
            for (type in listOf("followUp", "steer")) {
                assertSame(failure, assertFailsWith<ObjectStorageException> {
                    service.addQueuedMessage("session-1", "alice", text, type, listOf(attachment))
                })
            }
            coVerify(exactly = 2) {
                files.saveImage("session-1", match { it.contentEquals(bytes) }, "png", "alice", "image/png")
            }
            coVerify(exactly = 0) { manager.saveSessionMessages(any(), any()) }
            verify(exactly = 0) {
                session.followUpWithId(any<UserMessage>())
                session.steerWithId(any<UserMessage>())
                session.promptWithHistory(any())
                execution.beginExecution(any(), any(), any(), any())
                stream.asFlow()
            }
        }

        @Test
        fun `preserves queued plain text without uploading`() = runTest {
            val plainText = "  Keep this queued text\nand whitespace  "
            val response = service.addQueuedMessage("session-1", "alice", plainText, "followUp")
            assertEquals("followUp-1", response.id)
            assertEquals(plainText, response.content)
            assertEquals(listOf(TextContent(plainText)), queued.captured.content)
            verify(exactly = 1) { session.followUpWithId(any<UserMessage>()) }
            coVerify(exactly = 0) { files.saveImage(any(), any(), any(), any(), any()) }
        }
    }
}
