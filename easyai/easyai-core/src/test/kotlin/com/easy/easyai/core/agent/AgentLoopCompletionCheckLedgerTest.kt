package com.easy.easyai.core.agent

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.core.event.AgentEndEvent
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.UserMessageAddedEvent
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.metadata.ChatGenerationMetadata
import org.springframework.ai.chat.metadata.ChatResponseMetadata
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.model.Generation
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.chat.prompt.Prompt
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import org.springframework.ai.chat.messages.AssistantMessage as SpringAiAssistantMsg

/**
 * A check whose behaviour is a scripted list of results; the last entry repeats once the
 * script is exhausted. Records what the loop ledger handed it so the tests can assert on it.
 */
private class ScriptedCheck(
    private val results: List<CompletionCheckResult>,
    private val cap: Int = AgentCompletionCheck.DEFAULT_MAX_NUDGES,
) : AgentCompletionCheck {

    val observedAttempts = mutableListOf<Int>()
    val observedPreviousSignatures = mutableListOf<String?>()
    val observedToolNames = mutableListOf<Set<String>>()
    private var calls = 0

    override fun maxNudges(): Int = cap

    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult {
        observedAttempts += input.nudgeAttempt
        observedPreviousSignatures += input.previousSignature
        observedToolNames += input.toolNamesInvoked
        val result = results[calls.coerceAtMost(results.lastIndex)]
        calls++
        return result
    }

    val callCount: Int get() = calls
}

/**
 * Kept separate from [ScriptedCheck] on purpose: the ledger is keyed by check class, so two
 * instances of the same class would share one budget entry.
 */
private class AlwaysContinueCheck(private val cap: Int = 10) : AgentCompletionCheck {
    override fun maxNudges(): Int = cap
    override suspend fun check(input: CompletionCheckInput): CompletionCheckResult =
        CompletionCheckResult.Continue(signature = "keep-going")
}

/**
 * Tests for the per-run nudge ledger in [AgentLoop].
 *
 * The ledger is what keeps a completion check from resuming the loop forever: each check may
 * only be honoured [AgentCompletionCheck.maxNudges] times per run, and it gets its own
 * attempt count and previous signature back through [CompletionCheckInput].
 */
class AgentLoopCompletionCheckLedgerTest {

    private var llmCalls = 0

    private fun createMockChatResponse(
        text: String = "",
        toolCalls: List<SpringAiAssistantMsg.ToolCall> = emptyList(),
        finishReason: String? = "stop"
    ): ChatResponse {
        val assistantMsg = if (toolCalls.isEmpty()) {
            SpringAiAssistantMsg(text)
        } else {
            SpringAiAssistantMsg.builder().content(text).toolCalls(toolCalls).build()
        }
        val genMetadata = mockk<ChatGenerationMetadata>(relaxed = true)
        every { genMetadata.finishReason } returns finishReason

        val generation = mockk<Generation>(relaxed = true)
        every { generation.output } returns assistantMsg
        every { generation.metadata } returns genMetadata

        val responseMetadata = mockk<ChatResponseMetadata>(relaxed = true)

        val response = mockk<ChatResponse>(relaxed = true)
        every { response.result } returns generation
        every { response.results } returns listOf(generation)
        every { response.metadata } returns responseMetadata
        return response
    }

    /** Repeats the last response forever, so a runaway loop stays observable via [llmCalls]. */
    private fun createMockChatModel(vararg responses: ChatResponse): ChatModel {
        val mock = mockk<ChatModel>()
        var index = 0
        every { mock.stream(any<Prompt>()) } answers {
            llmCalls++
            val resp = responses[index.coerceAtMost(responses.size - 1)]
            index++
            reactor.core.publisher.Flux.just(resp)
        }
        return mock
    }

    private fun createMockChatModelFactory(chatModel: ChatModel): ChatModelFactory {
        val factory = mockk<ChatModelFactory>(relaxed = true)
        every { factory.create(any(), any()) } returns chatModel
        every { factory.build(any(), any(), any()) } returns ChatOptions.builder().model("test-model").build()
        every { factory.supports(any()) } returns true
        return factory
    }

    private fun createTestTool() =
        object : BaseToolDefinition(ToolMetadata(name = "echo", description = "Test tool")) {
            override fun parameterType(): Class<*> = Map::class.java
            override suspend fun doExecute(
                agentContext: AgentContext,
                toolCallId: String,
                messageId: String?,
                args: Map<String, Any?>,
                coroutineScope: kotlinx.coroutines.CoroutineScope,
                onUpdate: suspend (ToolUpdate) -> Unit
            ): ToolResult = ToolResult(content = listOf(TextContent("tool result")))
        }

    private class RecordingListener(val events: CopyOnWriteArrayList<AgentEvent>) : AgentEventListener {
        override suspend fun handle(agentContext: AgentContext, event: AgentEvent, push: suspend (AgentEvent) -> Unit) {
            events.add(event)
        }
    }

    private fun servicesWith(
        chatModel: ChatModel,
        checks: List<AgentCompletionCheck>,
        events: CopyOnWriteArrayList<AgentEvent>,
    ): DefaultAgentService {
        val promptService = mockk<PromptTemplateService>(relaxed = true)
        every { promptService.build(any(), any()) } returns "test prompt"
        return DefaultAgentService(
            chatModelFactories = listOf(createMockChatModelFactory(chatModel)),
            messageConverter = DefaultMessageConverter(),
            toolExecutor = DefaultToolExecutionEngine(),
            promptTemplateService = promptService,
            defaultChatModel = chatModel,
            eventListeners = listOf(RecordingListener(events)),
            completionChecks = checks,
        )
    }

    private class Run(val events: List<AgentEvent>, val llmCalls: Int)

    private suspend fun runAgent(
        chatModel: ChatModel,
        checks: List<AgentCompletionCheck>,
        maxIterations: Int,
        tools: List<com.easy.easyai.core.tool.ToolDefinition> = emptyList(),
    ): Run {
        llmCalls = 0
        val events = CopyOnWriteArrayList<AgentEvent>()
        val context = AgentContext(
            agentId = "test",
            customInstructions = "test",
            tools = tools,
            maxIterations = maxIterations,
        )
        val runner = AgentRunner(
            agent = Agent(context, servicesWith(chatModel, checks, events)),
            messages = mutableListOf()
        )
        runner.prompt(listOf(UserMessage("do work"))).result()
        return Run(events.toList(), llmCalls)
    }

    private fun endReasonOf(events: List<AgentEvent>): String =
        events.filterIsInstance<AgentEndEvent>().lastOrNull()?.endReason ?: "unknown"

    @Nested
    inner class `nudge ledger` {

        @Test
        fun `a check that keeps asking to continue is stopped once its cap is spent`() = runBlocking {
            val check = ScriptedCheck(
                results = listOf(CompletionCheckResult.Continue(signature = "unchanged")),
                cap = 2,
            )
            val textResponse = createMockChatResponse(text = "I think I am done")
            val chatModel = createMockChatModel(textResponse)

            val events = runAgent(chatModel, listOf(check), maxIterations = 20)

            // Two nudges honoured, then the third request is refused: exactly three LLM iterations.
            assertEquals(3, events.llmCalls, "loop must not keep resuming past the check's nudge cap")
            assertEquals(listOf(0, 1, 2), check.observedAttempts)
            assertEquals(AgentCompletionCheck.END_REASON_STALLED, endReasonOf(events.events))
        }

        @Test
        fun `the loop hands back the signature this check submitted last time`() = runBlocking {
            val check = ScriptedCheck(
                results = listOf(
                    CompletionCheckResult.Continue(signature = "sig-1"),
                    CompletionCheckResult.Continue(signature = "sig-2"),
                    CompletionCheckResult.Stalled(notice = "no progress"),
                ),
                cap = 5,
            )
            val chatModel = createMockChatModel(createMockChatResponse(text = "not finished"))

            runAgent(chatModel, listOf(check), maxIterations = 20)

            assertEquals(listOf(null, "sig-1", "sig-2"), check.observedPreviousSignatures)
        }

        @Test
        fun `Done clears the ledger so a later regression gets a fresh budget`() = runBlocking {
            // "Done" ends the run only when no other check asks to continue, so a second check
            // keeps the loop alive long enough to observe what the first one is credited with.
            val check = ScriptedCheck(
                results = listOf(
                    CompletionCheckResult.Continue(signature = "a"),
                    CompletionCheckResult.Done,
                    CompletionCheckResult.Continue(signature = "b"),
                    CompletionCheckResult.Stalled(notice = "giving up"),
                ),
                cap = 1,
            )
            val keepAlive = AlwaysContinueCheck(cap = 10)
            val chatModel = createMockChatModel(createMockChatResponse(text = "still going"))

            val events = runAgent(chatModel, listOf(check, keepAlive), maxIterations = 20)

            // Attempt count restarts from 0 after Done instead of accumulating across the run.
            assertEquals(listOf(0, 1, 0, 1), check.observedAttempts)
            assertEquals(AgentCompletionCheck.END_REASON_STALLED, endReasonOf(events.events))
        }

        @Test
        fun `tools executed during the run reach the check even after the transcript moved on`() = runBlocking {
            val check = ScriptedCheck(results = listOf(CompletionCheckResult.Done))
            val toolCallResponse = createMockChatResponse(
                text = "calling echo",
                toolCalls = listOf(SpringAiAssistantMsg.ToolCall("call1", "function", "echo", "{}")),
                finishReason = "tool_calls"
            )
            val chatModel = createMockChatModel(toolCallResponse, createMockChatResponse(text = "done"))

            runAgent(chatModel, listOf(check), maxIterations = 20, tools = listOf(createTestTool()))

            assertEquals(listOf(setOf("echo")), check.observedToolNames)
        }
    }

    @Nested
    inner class `stall surfacing` {

        @Test
        fun `notice is emitted as UserMessage with the completion_check source`() = runBlocking {
            val notice = "仍有 2 项未完成，已停止自动续跑。"
            val check = ScriptedCheck(results = listOf(CompletionCheckResult.Stalled(notice = notice)))
            val chatModel = createMockChatModel(createMockChatResponse(text = "wrapping up"))

            val events = runAgent(chatModel, listOf(check), maxIterations = 20)

            val noticeEvent = events.events.filterIsInstance<UserMessageAddedEvent>().single()
            assertEquals(notice, noticeEvent.content)
            assertEquals(UserMessage.SOURCE_COMPLETION_CHECK, noticeEvent.metadata[UserMessage.SOURCE_KEY])
            assertEquals(AgentCompletionCheck.END_REASON_STALLED, endReasonOf(events.events))
        }

        @Test
        fun `a stalled check without notice still ends the run instead of spinning`() = runBlocking {
            val check = ScriptedCheck(results = listOf(CompletionCheckResult.Stalled()))
            val chatModel = createMockChatModel(createMockChatResponse(text = "wrapping up"))

            val events = runAgent(chatModel, listOf(check), maxIterations = 20)

            assertEquals(1, events.llmCalls)
            assertEquals(emptyList<UserMessageAddedEvent>(), events.events.filterIsInstance<UserMessageAddedEvent>())
            assertEquals(AgentCompletionCheck.END_REASON_STALLED, endReasonOf(events.events))
        }

        @Test
        fun `an explicit stall reason is not overwritten by max_iterations`() = runBlocking {
            // maxIterations = 1, so the derived reason would be "max_iterations" without the
            // precedence guard on the explicitly assigned one.
            val check = ScriptedCheck(results = listOf(CompletionCheckResult.Stalled(notice = "stopped")))
            val chatModel = createMockChatModel(createMockChatResponse(text = "wrapping up"))

            val events = runAgent(chatModel, listOf(check), maxIterations = 1)

            assertEquals(AgentCompletionCheck.END_REASON_STALLED, endReasonOf(events.events))
        }
    }
}
