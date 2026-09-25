package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.event.PermissionRequestEvent
import com.easy.easyai.core.event.ToolExecutionEndEvent
import com.easy.easyai.core.event.UserMessageAddedEvent
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.StopReason
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.model.ToolResultEntry
import com.easy.easyai.core.model.ToolResultMessage
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.Prompt
import reactor.core.publisher.Flux
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.ai.chat.messages.AssistantMessage as SpringAssistantMessage
import org.springframework.ai.chat.messages.UserMessage as SpringUserMessage

internal class AgentLoopReInvocationTest {

    private fun call(id: String, name: String = "probe", args: String = "{}") =
        SpringAssistantMessage.ToolCall(id, "function", name, args)

    private fun messageText(message: UserMessage): String =
        message.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun response(vararg calls: SpringAssistantMessage.ToolCall): ChatResponse = ChatResponse(
        listOf(Generation(
            SpringAssistantMessage.builder().content(if (calls.isEmpty()) "done" else "").toolCalls(calls.toList()).build(),
            ChatGenerationMetadata.builder().finishReason(if (calls.isEmpty()) "stop" else "tool_calls").build()
        ))
    )

    private fun testTool(
        name: String = "probe",
        execute: suspend (String) -> ToolResult = { ToolResult(content = listOf(TextContent("unchanged"))) }
    ): ToolDefinition = object : BaseToolDefinition(ToolMetadata(
        name = name,
        description = "Test tool",
        skipOnResume = name == "ask_question"
    )) {
        override fun parameterType(): Class<*> = Map::class.java
        override suspend fun doExecute(
            agentContext: AgentContext,
            toolCallId: String,
            messageId: String?,
            args: Map<String, Any?>,
            coroutineScope: CoroutineScope,
            onUpdate: suspend (ToolUpdate) -> Unit
        ): ToolResult = execute(toolCallId)
    }

    private inner class Fixture(
        responses: List<ChatResponse>,
        tools: List<ToolDefinition> = listOf(testTool()),
        maxIterations: Int = 8
    ) {
        val transcript = mutableListOf<EasyAiMessage>()
        val persisted = CopyOnWriteArrayList<EasyAiMessage>()
        val prompts = CopyOnWriteArrayList<Prompt>()
        val events = CopyOnWriteArrayList<AgentEvent>()
        val pauses = CopyOnWriteArrayList<String>()
        val beforeIds = CopyOnWriteArrayList<String>()
        val afterIds = CopyOnWriteArrayList<String>()
        val steeringQueue = PendingMessageQueue()
        val followUpQueue = PendingMessageQueue()
        var aborted = false
        var before: (BeforeToolCallContext) -> BeforeToolCallResult = { BeforeToolCallResult.Allow }
        var after: (AfterToolCallContext) -> Unit = {}
        private val model = mockk<ChatModel>()
        private val context = AgentContext(agentId = "test", sessionId = "session", userId = "alice", tools = tools, maxIterations = maxIterations)
        private val services: AgentService

        init {
            every { model.stream(any<Prompt>()) } answers {
                val index = prompts.size
                prompts.add(firstArg())
                Flux.just(responses[index])
            }
            val promptService = mockk<PromptTemplateService>()
            every { promptService.build(any(), any()) } returns "test prompt"
            val base = DefaultAgentService(
                chatModelFactories = emptyList(),
                messageConverter = DefaultMessageConverter(),
                toolExecutor = DefaultToolExecutionEngine(),
                promptTemplateService = promptService,
                defaultChatModel = model,
                messageListener = object : MessageListener {
                    override suspend fun onMessageAdded(messages: List<EasyAiMessage>) {
                        persisted.addAll(messages)
                    }
                },
                eventListeners = listOf(object : AgentEventListener {
                    override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
                        events.add(event)
                    }
                }),
                waitForUserListener = object : WaitForUserListener {
                    override suspend fun onWaitForUser(sessionId: String, userId: String, reason: String) {
                        pauses.add(reason)
                    }
                }
            )
            services = object : AgentService by base {
                override val beforeToolCall = BeforeToolCallHook {
                    beforeIds.add(it.toolCallId)
                    before(it)
                }
                override val afterToolCall = AfterToolCallHook {
                    afterIds.add(it.toolCallId)
                    after(it)
                    AfterToolCallResult.Default
                }
            }
        }

        suspend fun run(messages: List<EasyAiMessage> = listOf(UserMessage("start"))) {
            AgentRunner(Agent(context, services), transcript, steeringQueue, followUpQueue, abortSignal = { aborted })
                .prompt(messages).result()
        }

        fun notices(): List<UserMessage> = transcript.filterIsInstance<UserMessage>().filter {
            it.metadata[UserMessage.SOURCE_KEY] == UserMessage.SOURCE_STEERING && messageText(it).startsWith("Repeated tool calls")
        }

        fun results(): List<ToolResultEntry> = transcript.filterIsInstance<ToolResultMessage>().flatMap { it.toolResults }
    }

    @Nested
    inner class `execution and message contracts` {

        @Test
        fun `warn and escalation follow complete unique results without skipping execution`() = runTest {
            val usage = Usage(inputTokens = 2, outputTokens = 1)
            val fixture = Fixture(
                listOf(response(call("c1")), response(call("c2")), response(call("c3")), response()),
                tools = listOf(testTool { id ->
                    ToolResult(
                        content = listOf(ToolResultContent(toolCallId = id, toolName = "probe", output = "{}", exitCode = 0, mimeType = "application/json")),
                        usage = usage
                    )
                })
            )
            fixture.run()

            assertEquals(listOf("c1", "c2", "c3"), fixture.beforeIds.toList())
            assertEquals(fixture.beforeIds.toList(), fixture.afterIds.toList())
            assertEquals(listOf("c1", "c2", "c3"), fixture.results().map { it.toolCallId })
            fixture.results().forEach {
                assertEquals("{}", it.result)
                assertEquals(0, it.exitCode)
                assertEquals("application/json", it.mimeType)
                assertEquals(usage, it.usage)
                assertFalse(it.isSkipped)
            }
            assertEquals(3, fixture.events.filterIsInstance<ToolExecutionEndEvent>().size)
            assertTrue(fixture.pauses.isEmpty())
            assertEquals(2, fixture.notices().size)
            assertTrue(messageText(fixture.notices()[0]).contains("[WARN]"))
            assertTrue(messageText(fixture.notices()[1]).contains("[ESCALATE]"))
            fixture.notices().forEach { notice ->
                val index = fixture.transcript.indexOf(notice)
                assertIs<ToolResultMessage>(fixture.transcript[index - 1])
                assertIs<AssistantMessage>(fixture.transcript[index - 2])
                assertEquals(1, fixture.persisted.count { it.id == notice.id })
                val persistedIndex = fixture.persisted.indexOfFirst { it.id == notice.id }
                assertIs<ToolResultMessage>(fixture.persisted[persistedIndex - 1])
                assertEquals(1, fixture.events.filterIsInstance<UserMessageAddedEvent>().count { it.messageId == notice.id })
            }
            fixture.prompts.forEach { prompt ->
                prompt.instructions.forEachIndexed { index, message ->
                    if (message is SpringAssistantMessage && message.toolCalls.isNotEmpty()) {
                        val result = assertIs<ToolResponseMessage>(prompt.instructions[index + 1])
                        assertEquals(message.toolCalls.map { it.id }, result.responses.map { it.id })
                    }
                }
            }
            assertTrue(assertNotNull(assertIs<SpringUserMessage>(fixture.prompts[2].instructions.last()).text).contains("[WARN]"))
            assertTrue(assertNotNull(assertIs<SpringUserMessage>(fixture.prompts[3].instructions.last()).text).contains("[ESCALATE]"))
        }

        @Test
        fun `changing real results never cause notices or duplicate responses`() = runTest {
            val fixture = Fixture(
                listOf(response(call("A")), response(call("B")), response(call("C")), response()),
                tools = listOf(testTool { ToolResult(content = listOf(TextContent(it))) })
            )
            fixture.run()
            assertEquals(listOf("A", "B", "C"), fixture.results().map { it.result })
            assertTrue(fixture.notices().isEmpty())
            assertTrue(fixture.pauses.isEmpty())
        }

        @Test
        fun `batch notices aggregate without hiding same-name different-argument calls`() = runTest {
            val fixture = Fixture(listOf(
                response(call("a1", args = """{"job":"a"}"""), call("b1", args = """{"job":"b"}""")),
                response(call("b2", args = """{"job":"b"}"""), call("a2", args = """{"job":"a"}""")),
                response()
            ))
            fixture.run()
            val text = messageText(fixture.notices().single())
            assertTrue(text.contains("call a2"))
            assertTrue(text.contains("call b2"))
            assertEquals(4, fixture.results().map { it.toolCallId }.distinct().size)
            assertEquals(1, fixture.events.filterIsInstance<UserMessageAddedEvent>().size)
        }

        @Test
        fun `model ignoring advice still executes until the existing iteration limit`() = runTest {
            val fixture = Fixture((1..4).map { response(call("c$it")) }, maxIterations = 4)
            fixture.run()
            assertEquals(4, fixture.results().size)
            assertEquals(3, fixture.notices().size)
            assertTrue(fixture.pauses.isEmpty())
            assertEquals("max_iterations", fixture.events.filterIsInstance<AgentEndEvent>().single().endReason)
        }
    }

    @Nested
    inner class `steering and available actions` {

        @Test
        fun `advice only names available waiting and question tools`() = runTest {
            for (names in listOf(emptyList(), listOf("bash"), listOf("ask_question"), listOf("bash", "ask_question"))) {
                val fixture = Fixture(
                    listOf(response(call("c1")), response(call("c2")), response()),
                    tools = listOf(testTool()) + names.map { testTool(it) }
                )
                fixture.run()
                val text = messageText(fixture.notices().single())
                assertEquals("bash" in names, text.contains("use bash"))
                assertEquals("ask_question" in names, text.contains("call ask_question alone"))
                assertFalse(text.contains("still running"))
                assertFalse(text.contains("user has been consulted"))
                assertFalse(text.contains("wait_for_member_events"))
            }
        }

        @Test
        fun `detector steering precedes external steering and preserves follow-up consumption`() = runTest {
            val fixture = Fixture(listOf(response(call("c1")), response(call("c2")), response(), response()))
            val external = UserMessage("Use a different strategy")
            val followUp = UserMessage("Explain the result")
            fixture.after = {
                if (it.toolCallId == "c2") {
                    fixture.steeringQueue.enqueueWithType(external, "steer")
                    fixture.followUpQueue.enqueueWithType(followUp, "followUp")
                }
            }
            fixture.run()
            val noticeIndex = fixture.transcript.indexOf(fixture.notices().single())
            assertEquals(external.id, fixture.transcript[noticeIndex + 1].id)
            assertEquals(1, fixture.persisted.count { it.id == external.id })
            assertEquals(1, fixture.persisted.count { it.id == followUp.id })
            assertEquals(messageText(external), fixture.prompts[2].instructions.last().text)
            assertEquals(messageText(followUp), fixture.prompts[3].instructions.last().text)
            assertTrue(fixture.steeringQueue.isEmpty())
            assertTrue(fixture.followUpQueue.isEmpty())
        }

        @Test
        fun `model can wait in a separate turn before retrying without forced pause`() = runTest {
            val fixture = Fixture(
                listOf(response(call("c1")), response(call("c2")), response(call("wait", "bash", """{"command":"sleep 1"}""")), response(call("c3")), response()),
                tools = listOf(testTool(), testTool("bash"))
            )
            fixture.run()
            assertEquals(listOf("c1", "c2", "wait", "c3"), fixture.beforeIds.toList())
            assertEquals(1, fixture.notices().size)
            assertTrue(fixture.pauses.isEmpty())
        }
    }

    @Nested
    inner class `real pause and resume` {

        @Test
        fun `only a model-selected question pauses and an answer resumes with fresh detection`() = runTest {
            val questionArgs = """{"questions":[{"question":"Continue?","options":[{"label":"Continue"},{"label":"Stop"}]}]}"""
            val fixture = Fixture(
                listOf(response(call("c1")), response(call("c2")), response(call("c3")), response(call("q1", "ask_question", questionArgs)), response(call("c4")), response()),
                tools = listOf(testTool(), testTool("ask_question") { ToolResult(content = emptyList(), needPause = true, pauseReason = "ask_question") })
            )
            fixture.run()
            assertEquals(listOf("ask_question"), fixture.pauses.toList())
            assertEquals(4, fixture.prompts.size)
            assertEquals(2, fixture.notices().size)
            assertTrue(fixture.results().none { it.toolCallId == "q1" })
            assertTrue(fixture.events.filterIsInstance<ToolExecutionEndEvent>().none { it.toolCallId == "q1" })
            val questionIndex = fixture.transcript.indexOfLast { it is AssistantMessage }
            assertTrue(fixture.transcript.drop(questionIndex + 1).all { it is ToolResultMessage })

            fixture.transcript.add(ToolResultMessage(toolResults = listOf(ToolResultEntry(
                toolCallId = "q1", toolName = "ask_question", result = "Continue"
            ))))
            fixture.run(emptyList())
            assertEquals(1, fixture.beforeIds.count { it == "q1" })
            assertEquals(listOf("c1", "c2", "c3", "q1", "c4"), fixture.beforeIds.toList())
            assertEquals(2, fixture.notices().size)
            assertEquals("Continue", fixture.results().single { it.toolCallId == "q1" }.result)
            assertEquals("normal", fixture.events.filterIsInstance<AgentEndEvent>().last().endReason)
        }

        @Test
        fun `question in a repeated batch prevents advice and keeps external steering queued`() = runTest {
            val fixture = Fixture(
                listOf(response(call("c1")), response(call("c2"), call("q1", "ask_question"))),
                tools = listOf(testTool(), testTool("ask_question") { ToolResult(content = emptyList(), needPause = true, pauseReason = "ask_question") })
            )
            val external = UserMessage("New direction")
            fixture.after = {
                if (it.toolCallId == "c2") fixture.steeringQueue.enqueueWithType(external, "steer")
            }
            fixture.run()
            assertTrue(fixture.notices().isEmpty())
            assertFalse(fixture.steeringQueue.isEmpty())
            assertTrue(fixture.persisted.none { it.id == external.id })
            assertEquals(listOf("c1", "c2"), fixture.results().map { it.toolCallId })
            assertEquals(listOf("ask_question"), fixture.pauses.toList())
        }

        @Test
        fun `permission pause retains skipped placeholders without generating advice`() = runTest {
            val fixture = Fixture(listOf(response(call("c1")), response(call("c2"), call("c3"))))
            fixture.before = {
                if (it.toolCallId == "c2") {
                    BeforeToolCallResult.PermissionRequest("shell.other", "probe", it.toolCallId, it.toolName, it.arguments)
                } else BeforeToolCallResult.Allow
            }
            fixture.run()
            assertTrue(fixture.notices().isEmpty())
            assertEquals(listOf("permission_request"), fixture.pauses.toList())
            assertEquals(listOf("c1"), fixture.afterIds.toList())
            assertEquals(listOf("c1", "c3"), fixture.results().map { it.toolCallId })
            assertTrue(fixture.results().single { it.toolCallId == "c3" }.isSkipped)
            assertEquals("c2", fixture.events.filterIsInstance<PermissionRequestEvent>().single().toolCallId)
        }

        @Test
        fun `abort after a repeated result exits before injecting advice`() = runTest {
            val fixture = Fixture(listOf(response(call("c1")), response(call("c2"))))
            fixture.after = { if (it.toolCallId == "c2") fixture.aborted = true }
            fixture.run()
            assertEquals(2, fixture.results().size)
            assertTrue(fixture.notices().isEmpty())
            assertEquals(StopReason.ABORTED, fixture.transcript.filterIsInstance<AssistantMessage>().last().stopReason)
            assertEquals("cancelled", fixture.events.filterIsInstance<AgentEndEvent>().single().endReason)
        }
    }
}
