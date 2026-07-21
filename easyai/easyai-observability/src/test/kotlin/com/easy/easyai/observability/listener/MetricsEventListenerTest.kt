package com.easy.easyai.observability.listener

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.*
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.observability.config.ObservabilityProperties
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MetricsEventListenerTest {

    private val registry = SimpleMeterRegistry()
    private val properties = ObservabilityProperties(metricsEnabled = true)
    private val listener = MetricsEventListener(registry, properties)
    private val dummyContext = AgentContext(agentId = "test")

    private fun handle(event: AgentEvent) {
        kotlinx.coroutines.runBlocking {
            listener.handle(dummyContext, event) { }
        }
    }

    @Nested
    inner class `Agent session metrics` {

        @Test
        fun `increments active sessions on start`() {
            handle(AgentStartEvent("session-1"))

            val gauge = registry.find("easyai.agent.active").gauge()
            assertEquals(1.0, gauge?.value())
        }

        @Test
        fun `decrements active sessions on end`() {
            handle(AgentStartEvent("session-1"))
            handle(AgentEndEvent("session-1", "completed"))

            val gauge = registry.find("easyai.agent.active").gauge()
            assertEquals(0.0, gauge?.value())
        }

        @Test
        fun `records session duration on end`() {
            handle(AgentStartEvent("session-1"))
            Thread.sleep(10) // Small delay to ensure non-zero duration
            handle(AgentEndEvent("session-1", "completed"))

            val timer = registry.find("easyai.agent.duration").timer()
            assertTrue(timer != null)
            assertTrue(timer.count() > 0)
        }

        @Test
        fun `increments total sessions counter`() {
            handle(AgentStartEvent("session-1"))

            val counter = registry.find("easyai.agent.sessions.total").counter()
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    inner class `Turn metrics` {

        @Test
        fun `increments turns counter`() {
            handle(TurnStartEvent(1, "session-1"))

            val counter = registry.find("easyai.turns.total").counter()
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    inner class `Message metrics` {

        @Test
        fun `increments messages counter`() {
            val message = AssistantMessage(
                id = "msg-1",
                content = listOf(TextContent("Hello")),
                usage = Usage(inputTokens = 10, outputTokens = 20)
            )
            handle(MessageEndEvent("msg-1", 0, "session-1", message))

            val counter = registry.find("easyai.messages.total").counter()
            assertEquals(1.0, counter?.count())
        }

        @Test
        fun `records token usage`() {
            val message = AssistantMessage(
                id = "msg-2",
                content = listOf(TextContent("Hello")),
                usage = Usage(inputTokens = 10, outputTokens = 20)
            )
            handle(MessageEndEvent("msg-2", 0, "session-1", message))

            val inputTokens = registry.find("easyai.llm.tokens.total")
                .tag("direction", "input")
                .counter()
            val outputTokens = registry.find("easyai.llm.tokens.total")
                .tag("direction", "output")
                .counter()

            assertEquals(10.0, inputTokens?.count())
            assertEquals(20.0, outputTokens?.count())
        }
    }

    @Nested
    inner class `Tool metrics` {

        @Test
        fun `increments tool calls counter`() {
            handle(ToolExecutionStartEvent("call-1", "read", mapOf("path" to "test.txt"), 0, "session-1"))

            val counter = registry.find("easyai.tool.calls.total")
                .tag("tool", "read")
                .counter()
            assertEquals(1.0, counter?.count())
        }

        @Test
        fun `increments tool errors counter on error`() {
            val result = com.easy.easyai.core.tool.ToolResult(content = emptyList())
            handle(ToolExecutionEndEvent("call-1", "read", result, turnId = 0, sessionId = "session-1", isError = true))

            val counter = registry.find("easyai.tool.errors.total")
                .tag("tool", "read")
                .counter()
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    inner class `Error metrics` {

        @Test
        fun `increments errors counter`() {
            handle(ErrorEvent(RuntimeException("Test error"), "session-1"))

            val counter = registry.find("easyai.agent.errors.total").counter()
            assertEquals(1.0, counter?.count())
        }
    }

    @Nested
    inner class `Metrics disabled` {

        @Test
        fun `does not record metrics when disabled`() {
            val disabledProperties = ObservabilityProperties(metricsEnabled = false)
            val disabledListener = MetricsEventListener(registry, disabledProperties)

            kotlinx.coroutines.runBlocking {
                disabledListener.handle(dummyContext, AgentStartEvent("session-1")) { }
            }

            val gauge = registry.find("easyai.agent.active").gauge()
            assertEquals(0.0, gauge?.value()) // Should remain 0
        }
    }
}
