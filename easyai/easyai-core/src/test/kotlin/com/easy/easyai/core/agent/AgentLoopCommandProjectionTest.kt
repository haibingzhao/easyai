package com.easy.easyai.core.agent

import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.FolderRefContent
import com.easy.easyai.core.model.ImageContent
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMessage
import org.springframework.ai.chat.messages.SystemMessage as SpringAiSystemMessage

class AgentLoopCommandProjectionTest {

    private fun command() = UserMessage(
        content = listOf(
            TextContent("/review captured"),
            ImageContent(byteArrayOf(1, 2), "image/png"),
            FolderRefContent("/project/src", "src", 8)
        ),
        metadata = mapOf(
            UserMessage.COMMAND_EXPANSION to "Use the server-authorized captured review instructions.",
            UserMessage.COMMAND_NAME to "review",
            UserMessage.COMMAND_USER_ID to "alice",
            UserMessage.COMMAND_PROJECT_PATH to "/project",
            "attachmentOwner" to "alice"
        )
    )

    private class Fixture(
        checks: List<AgentCompletionCheck> = emptyList(),
        systemPrompt: String = ""
    ) {
        val convertedSnapshots = CopyOnWriteArrayList<List<EasyAiMessage>>()
        val prompts = CopyOnWriteArrayList<Prompt>()
        val persisted = CopyOnWriteArrayList<EasyAiMessage>()
        val context = AgentContext(agentId = "test", sessionId = "session", userId = "alice", maxIterations = 5)
        val model = mockk<ChatModel>()
        private val converter = DefaultMessageConverter()
        val services: AgentService

        init {
            every { model.stream(any<Prompt>()) } answers {
                prompts.add(firstArg())
                Flux.just(ChatResponse(listOf(Generation(SpringAiAssistantMessage("done")))))
            }
            val promptService = mockk<PromptTemplateService>()
            every { promptService.build(any(), any()) } returns systemPrompt
            services = DefaultAgentService(
                chatModelFactories = emptyList(),
                messageConverter = object : MessageConverter by converter {
                    override suspend fun toSpringAiMessages(messages: List<EasyAiMessage>, userId: String): List<Message> {
                        assertEquals("alice", userId)
                        convertedSnapshots.add(messages)
                        return converter.toSpringAiMessages(messages, userId)
                    }
                },
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = promptService,
                defaultChatModel = model,
                messageListener = object : MessageListener {
                    override suspend fun onMessageAdded(messages: List<EasyAiMessage>) {
                        persisted.addAll(messages)
                    }
                },
                completionChecks = checks
            )
        }
    }

    @Nested
    inner class PromptSnapshots {

        @Test
        fun `preparePrompt builds fresh projections and persists only the base system prompt once`() = runTest {
            val fixture = Fixture(systemPrompt = "Base system prompt")
            val command = command()
            val transcript = mutableListOf<EasyAiMessage>(command)
            val runner = AgentLoopRunner(fixture.context, fixture.model, fixture.services)
            val first = runner.preparePrompt(transcript, emptyList())
            val second = runner.preparePrompt(transcript, emptyList())

            assertEquals(listOf<EasyAiMessage>(command), transcript)
            assertNotSame(fixture.convertedSnapshots[0], fixture.convertedSnapshots[1])
            assertSame(command, fixture.convertedSnapshots[0].last())
            for (prompt in listOf(first, second)) {
                assertEquals(listOf("Base system prompt", command.metadata[UserMessage.COMMAND_EXPANSION]),
                    prompt.instructions.filterIsInstance<SpringAiSystemMessage>().map { it.text })
            }
            assertEquals(listOf("Base system prompt"), fixture.persisted.filterIsInstance<SystemMessage>().map { it.text })
            assertEquals(1, fixture.persisted.size)
        }

        @Test
        fun `ordinary command is projected on each turn and replay without duplicate persistence`() = runTest {
            val fixture = Fixture()
            val command = command()
            val transcript = mutableListOf<EasyAiMessage>()
            val followUp = PendingMessageQueue()
            followUp.enqueueWithType(UserMessage("continue"), "followUp")
            AgentRunner(Agent(fixture.context, fixture.services), transcript, followUpQueue = followUp)
                .prompt(listOf(command)).result()

            assertEquals(2, fixture.prompts.size)
            assertEquals(1, transcript.count { it.id == command.id })
            assertEquals(1, fixture.persisted.count { it.id == command.id })
            assertTrue(transcript.none { it is SystemMessage })
            assertTrue(fixture.persisted.none { it is SystemMessage })
            val replay = AgentLoopRunner(fixture.context, fixture.model, fixture.services)
            replay.preparePrompt(transcript, emptyList())
            for (snapshot in fixture.convertedSnapshots) {
                assertEquals(1, snapshot.filterIsInstance<SystemMessage>().size)
                assertEquals(command.metadata[UserMessage.COMMAND_EXPANSION], snapshot.filterIsInstance<SystemMessage>().single().text)
                assertSame(command, snapshot.first { it.id == command.id })
            }
            assertEquals(1, fixture.persisted.count { it.id == command.id })
        }
    }

    @Nested
    inner class QueueConsumption {

        private suspend fun assertQueueProjection(type: String, late: Boolean) {
            val queue = PendingMessageQueue()
            val command = command()
            var queueId: String? = null
            val enqueued = AtomicBoolean(false)
            val checks = if (late) listOf(object : AgentCompletionCheck {
                override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
                    if (enqueued.compareAndSet(false, true)) {
                        queueId = queue.enqueueWithType(command, type)
                    }
                    return CompletionCheckResult.Done
                }
            }) else emptyList()
            if (!late) queueId = queue.enqueueWithType(command, type)
            val fixture = Fixture(checks)
            val transcript = mutableListOf<EasyAiMessage>()
            val runner = AgentRunner(
                agent = Agent(fixture.context, fixture.services),
                messages = transcript,
                steeringQueue = if (type == "steer") queue else PendingMessageQueue(),
                followUpQueue = if (type == "followUp") queue else PendingMessageQueue()
            )
            runner.prompt(listOf(UserMessage("initial request"))).result()

            assertEquals(2, fixture.prompts.size)
            assertTrue(fixture.convertedSnapshots[0].none { it is SystemMessage })
            val snapshot = fixture.convertedSnapshots[1]
            val commandIndex = snapshot.indexOfFirst { it.id == command.id }
            assertEquals(command.metadata[UserMessage.COMMAND_EXPANSION], (snapshot[commandIndex - 1] as SystemMessage).text)
            assertEquals(1, snapshot.filterIsInstance<SystemMessage>().size)
            val saved = fixture.persisted.filterIsInstance<UserMessage>().single { it.id == command.id }
            assertEquals(command.content, saved.content)
            assertEquals(command.metadata + (UserMessage.SOURCE_KEY to if (type == "steer") UserMessage.SOURCE_STEERING else UserMessage.SOURCE_FOLLOW_UP), saved.metadata)
            assertTrue(transcript.none { it is SystemMessage })
            assertTrue(fixture.persisted.none { it is SystemMessage })
            assertTrue(queue.isEmpty())
            assertNull(queue.get(requireNotNull(queueId)))
            assertFalse(queue.updateMessage(requireNotNull(queueId), command, command.copy()))
        }

        @Test
        fun `session queue edit consumes the replacement expansion rather than the old snapshot`() = runTest {
            val fixture = Fixture()
            val session = ChatSession("session", Agent(fixture.context, fixture.services))
            val original = command()
            val id = session.followUpWithId(original)
            val snapshot = assertNotNull(session.getQueuedMessage(id))
            val replacement = snapshot.copy(metadata = snapshot.metadata + (UserMessage.COMMAND_EXPANSION to "New authorized snapshot"))
            assertTrue(session.updateQueuedMessage(id, snapshot, replacement))
            session.promptWithHistory(listOf(UserMessage("initial request"))).result()

            assertEquals(2, fixture.prompts.size)
            val projected = fixture.convertedSnapshots[1]
            assertEquals(listOf("New authorized snapshot"), projected.filterIsInstance<SystemMessage>().map { it.text })
            val saved = fixture.persisted.filterIsInstance<UserMessage>().single { it.id == original.id }
            assertEquals("New authorized snapshot", saved.metadata[UserMessage.COMMAND_EXPANSION])
            assertEquals(original.content, saved.content)
            assertNull(session.getQueuedMessage(id))
            assertFalse(session.updateQueuedMessage(id, replacement, original))
            assertTrue(fixture.persisted.none { it is SystemMessage })
        }

        @Test
        fun `steering consumption keeps the authorized snapshot`() = runTest {
            assertQueueProjection("steer", late = false)
        }

        @Test
        fun `follow-up consumption keeps the authorized snapshot`() = runTest {
            assertQueueProjection("followUp", late = false)
        }

        @Test
        fun `late steering consumption projects on the next turn`() = runTest {
            assertQueueProjection("steer", late = true)
        }

        @Test
        fun `late follow-up consumption projects on the next turn`() = runTest {
            assertQueueProjection("followUp", late = true)
        }
    }
}
