package com.easy.easyai.autoconfigure.dashscope

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [DashScopeStreamingAccumulator], the piece that decides whether the agent loop sees
 * complete tool call arguments.
 *
 * The loop only reads tool calls off the last content-bearing chunk, so the adapter has to publish
 * a full snapshot on every frame — and Bailian may stream either positional fragments or growing
 * array snapshots, which are only distinguishable by prefix comparison.
 */
internal class DashScopeStreamingAccumulatorTest {

    private fun fragment(
        slot: Int = 0,
        id: String? = null,
        name: String? = null,
        arguments: String? = null
    ) = DashScopeToolCallFragment(slot = slot, id = id, name = name, arguments = arguments)

    private fun chunk(arguments: String?, slot: Int = 0) =
        DashScopeChunk(requestId = "req", reasoningDelta = null, textDelta = null, toolCallFragments = listOf(fragment(slot = slot, arguments = arguments)))

    @Nested
    inner class `positional fragments` {

        @Test
        fun `appends argument pieces in order`() {
            val accumulator = DashScopeStreamingAccumulator()
            accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = null,
                    textDelta = null,
                    toolCallFragments = listOf(fragment(id = "call_1", name = "get_weather", arguments = "{\"ci"))
                )
            )
            val frame = accumulator.accept(chunk("ty\":\"Hang"))
            assertEquals(listOf(DashScopeToolCallSpec("call_1", "get_weather", "{\"city\":\"Hang")), frame.toolCalls)

            val last = accumulator.accept(chunk("zhou\"}"))
            assertEquals(listOf(DashScopeToolCallSpec("call_1", "get_weather", "{\"city\":\"Hangzhou\"}")), last.toolCalls)
        }

        @Test
        fun `publishes the accumulated snapshot on every frame`() {
            val accumulator = DashScopeStreamingAccumulator()
            accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = null,
                    textDelta = null,
                    toolCallFragments = listOf(fragment(id = "call_1", name = "tool", arguments = "a"))
                )
            )

            val frames = listOf("b", "c").map { accumulator.accept(chunk(it)) }

            // Each frame repeats what came before, so the loop's last content chunk is complete.
            assertEquals(listOf("ab", "abc"), frames.map { it.toolCalls.single().arguments })
        }
    }

    @Nested
    inner class `array snapshots` {

        @Test
        fun `replaces instead of duplicating a prefix-repeating frame`() {
            val accumulator = DashScopeStreamingAccumulator()
            accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = null,
                    textDelta = null,
                    toolCallFragments = listOf(fragment(id = "call_1", name = "tool", arguments = "{\"a\""))
                )
            )

            val frame = accumulator.accept(chunk("{\"a\":1}"))

            assertEquals(listOf(DashScopeToolCallSpec("call_1", "tool", "{\"a\":1}")), frame.toolCalls)
        }

        @Test
        fun `keeps a call stable while a later slot appears`() {
            val accumulator = DashScopeStreamingAccumulator()
            accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = null,
                    textDelta = null,
                    toolCallFragments = listOf(fragment(slot = 0, id = "call_1", name = "first", arguments = "{}"))
                )
            )

            val frame = accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = null,
                    textDelta = null,
                    toolCallFragments = listOf(fragment(slot = 1, id = "call_2", name = "second", arguments = "{\"x\":1}"))
                )
            )

            assertEquals(
                listOf(
                    DashScopeToolCallSpec("call_1", "first", "{}"),
                    DashScopeToolCallSpec("call_2", "second", "{\"x\":1}")
                ),
                frame.toolCalls
            )
        }
    }

    @Nested
    inner class `passthrough` {

        @Test
        fun `carries thinking text, answer text, finish reason and usage`() {
            val usage = DashScopeTokenUsage(10, 4, 14, 8L, 2L, 3)
            val accumulator = DashScopeStreamingAccumulator()

            val frame = accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = "thinking",
                    textDelta = "hello",
                    finishReason = "stop",
                    usage = usage
                )
            )

            assertEquals("thinking", frame.reasoningDelta)
            assertEquals("hello", frame.textDelta)
            assertEquals("stop", frame.finishReason)
            assertEquals(usage, frame.usage)
            assertTrue(frame.toolCalls.isEmpty())
        }

        @Test
        fun `leaves finish reason absent when the provider omitted it`() {
            val accumulator = DashScopeStreamingAccumulator()

            val frame = accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = null,
                    textDelta = "partial",
                    toolCallFragments = listOf(fragment(id = "call_1", name = "tool", arguments = "{}"))
                )
            )

            assertNull(frame.finishReason)
        }

        @Test
        fun `forwards an in-band error frame`() {
            val accumulator = DashScopeStreamingAccumulator()
            val error = DashScopeError(429, "Throttling", "too many requests")

            val frame = accumulator.accept(
                DashScopeChunk(requestId = "req", reasoningDelta = null, textDelta = null, error = error)
            )

            assertEquals(error, frame.error)
        }
    }

    @Nested
    inner class `incomplete calls` {

        @Test
        fun `drops a call that never reported a name`() {
            val accumulator = DashScopeStreamingAccumulator()

            val frame = accumulator.accept(
                DashScopeChunk(
                    requestId = "req",
                    reasoningDelta = null,
                    textDelta = null,
                    toolCallFragments = listOf(fragment(id = "call_1", arguments = "{}"))
                )
            )

            assertTrue(frame.toolCalls.isEmpty())
        }
    }
}
