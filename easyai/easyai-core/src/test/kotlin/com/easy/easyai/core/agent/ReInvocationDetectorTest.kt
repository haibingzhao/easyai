package com.easy.easyai.core.agent

import com.easy.easyai.core.agent.ReInvocationDetector.Level
import com.easy.easyai.core.agent.ReInvocationDetector.Notice
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.tool.ToolCallResult
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

internal class ReInvocationDetectorTest {

    private val detector = ReInvocationDetector()

    @Nested
    inner class Outcomes {

        @Test
        fun `warns at two and escalates at three and four identical completions`() {
            val calls = (1..4).map { call(it.toString()) }
            assertTrue(observe(call = calls[0]).isEmpty())
            assertEquals(listOf(notice(calls[1])), observe(call = calls[1]))
            assertEquals(listOf(notice(calls[2], 3, Level.ESCALATE)), observe(call = calls[2]))
            assertEquals(listOf(notice(calls[3], 4, Level.ESCALATE)), observe(call = calls[3]))
        }

        @Test
        fun `never warns for successive A B C results`() {
            for (text in listOf("A", "B", "C")) {
                assertTrue(observe(text).isEmpty())
            }
        }

        @Test
        fun `result change resets an established streak`() {
            assertTrue(observe("pending").isEmpty())
            assertEquals(listOf(notice()), observe("pending"))
            assertTrue(observe("finished").isEmpty())
            assertEquals(listOf(notice()), observe("finished"))
        }

        @Test
        fun `error flag changes reset while identical errors count`() {
            assertTrue(observe().isEmpty())
            assertEquals(listOf(notice()), observe())
            assertTrue(observe(isError = true).isEmpty())
            assertEquals(listOf(notice()), observe(isError = true))
            assertEquals(listOf(notice(count = 3, level = Level.ESCALATE)), observe(isError = true))
            assertTrue(observe().isEmpty())
            assertEquals(listOf(notice()), observe())
        }

        @Test
        fun `empty result text participates and differs from nonempty text`() {
            assertTrue(observe("").isEmpty())
            assertEquals(listOf(notice()), observe(""))
            assertTrue(observe("pending").isEmpty())
            assertTrue(observe("").isEmpty())
            assertEquals(listOf(notice()), observe(""))
        }
    }

    @Nested
    inner class Keys {

        @Test
        fun `raw argument changes start independent streaks without canonicalization`() {
            val compact = call(arguments = "{\"job\":1}")
            val spaced = call(arguments = "{ \"job\": 1 }")
            assertTrue(observe(call = compact).isEmpty())
            assertEquals(listOf(notice(compact)), observe(call = compact))
            assertTrue(observe(call = spaced).isEmpty())
            assertEquals(listOf(notice(spaced)), observe(call = spaced))
        }

        @Test
        fun `changed tool name starts a new streak`() {
            val renamed = call(name = "other_poll")
            assertTrue(observe().isEmpty())
            assertEquals(listOf(notice()), observe())
            assertTrue(observe(call = renamed).isEmpty())
            assertEquals(listOf(notice(renamed)), observe(call = renamed))
        }

        @Test
        fun `parallel calls with the same name and different arguments each warn`() {
            val first = listOf(call("a1", arguments = "A"), call("b1", arguments = "B"))
            val second = listOf(call("a2", arguments = "A"), call("b2", arguments = "B"))
            assertTrue(detector.observeTurn(first, first.map { result(it, it.arguments) }).isEmpty())
            assertEquals(
                second.map { notice(it) },
                detector.observeTurn(second, second.map { result(it, it.arguments) })
            )
        }
    }

    @Nested
    inner class Batches {

        @Test
        fun `same key duplicates increment within a batch and continue the next turn`() {
            val first = listOf(call("1"), call("2"))
            val second = listOf(call("3"), call("4"))
            assertEquals(
                listOf(notice(first.last())),
                detector.observeTurn(first, first.map { result(it) })
            )
            assertEquals(
                listOf(notice(second.last(), 4, Level.ESCALATE)),
                detector.observeTurn(second, second.map { result(it) })
            )
        }

        @Test
        fun `reversed results align by id and count in assistant order`() {
            val calls = listOf(call("1"), call("2"), call("3"))
            val results = listOf(result(calls[0], "old"), result(calls[1], "new"), result(calls[2], "new"))
            assertEquals(listOf(notice(calls.last())), detector.observeTurn(calls, results.reversed()))
            assertEquals(listOf(notice(count = 3, level = Level.ESCALATE)), observe("new"))
        }

        @Test
        fun `last result change suppresses a stale warning for the key`() {
            assertTrue(observe().isEmpty())
            val calls = listOf(call("1"), call("2"), call("3"))
            val results = listOf(result(calls[0]), result(calls[1]), result(calls[2], "finished"))
            assertTrue(detector.observeTurn(calls, results).isEmpty())
            assertEquals(listOf(notice()), observe("finished"))
        }

        @Test
        fun `final notices use last eligible ids and last contributing assistant order`() {
            val seed = listOf(call("a0", name = "A"), call("b0", name = "B"))
            assertTrue(detector.observeTurn(seed, seed.map { result(it) }).isEmpty())
            val calls = listOf(call("a1", name = "A"), call("b1", name = "B"), call("a2", name = "A"))
            assertEquals(
                listOf(notice(calls[1]), notice(calls[2], 3, Level.ESCALATE)),
                detector.observeTurn(calls, calls.map { result(it) }.reversed())
            )
        }
    }

    @Nested
    inner class Boundaries {

        @Test
        fun `missing results clear batch streaks without reviving the previous turn`() {
            assertBoundaryBreaksStreak { null }
        }

        @Test
        fun `paused results clear batch streaks without reviving the previous turn`() {
            assertBoundaryBreaksStreak { result(it, needPause = true) }
        }

        @Test
        fun `skipped results clear batch streaks without reviving the previous turn`() {
            assertBoundaryBreaksStreak { result(it, isSkipped = true) }
        }

        @Test
        fun `absent keys reset while present keys keep their streaks`() {
            val a = call("a", name = "A")
            val b = call("b", name = "B")
            val both = listOf(a, b)
            assertTrue(detector.observeTurn(both, both.map { result(it) }).isEmpty())
            assertEquals(both.map { notice(it) }, detector.observeTurn(both, both.map { result(it) }))
            assertEquals(listOf(notice(b, 3, Level.ESCALATE)), observe(call = b))
            assertEquals(
                listOf(notice(b, 4, Level.ESCALATE)),
                detector.observeTurn(both, both.map { result(it) })
            )
        }

        @Test
        fun `no tool turn clears all streaks even with unmatched results`() {
            assertTrue(observe().isEmpty())
            assertEquals(listOf(notice()), observe())
            assertTrue(detector.observeTurn(emptyList(), listOf(result(call()))).isEmpty())
            assertTrue(observe().isEmpty())
            assertEquals(listOf(notice()), observe())
        }

        @Test
        fun `fresh detector does not inherit another detector streak`() {
            assertTrue(observe().isEmpty())
            assertEquals(listOf(notice()), observe())
            val fresh = ReInvocationDetector()
            assertTrue(fresh.observeTurn(listOf(call()), listOf(result(call()))).isEmpty())
            assertEquals(listOf(notice()), fresh.observeTurn(listOf(call()), listOf(result(call()))))
        }
    }

    private fun assertBoundaryBreaksStreak(boundary: (ToolCallContent) -> ToolCallResult?) {
        for (boundaryPosition in 0..2) {
            val detector = ReInvocationDetector()
            val seed = call("seed")
            assertTrue(detector.observeTurn(listOf(seed), listOf(result(seed))).isEmpty())
            assertEquals(listOf(notice(seed)), detector.observeTurn(listOf(seed), listOf(result(seed))))
            val calls = (0..2).map { call(it.toString()) }
            val results = calls.mapIndexedNotNull { position, call ->
                if (position == boundaryPosition) boundary(call) else result(call)
            }
            val expected = if (boundaryPosition == 0) listOf(notice(calls.last())) else emptyList()
            assertEquals(expected, detector.observeTurn(calls, results), "boundary at $boundaryPosition")
            val next = call("next")
            val nextExpected = when (boundaryPosition) {
                0 -> listOf(notice(next, 3, Level.ESCALATE))
                1 -> listOf(notice(next))
                else -> emptyList()
            }
            assertEquals(nextExpected, detector.observeTurn(listOf(next), listOf(result(next))))
            if (boundaryPosition == 2) {
                assertEquals(listOf(notice(next)), detector.observeTurn(listOf(next), listOf(result(next))))
            }
        }
    }

    private fun call(id: String = "call", name: String = "poll", arguments: String = "{}") =
        ToolCallContent(id = id, name = name, arguments = arguments)

    private fun result(
        call: ToolCallContent,
        text: String = "pending",
        isError: Boolean = false,
        needPause: Boolean = false,
        isSkipped: Boolean = false
    ) = ToolCallResult(
        toolCallId = call.id,
        resultText = text,
        isError = isError,
        needPause = needPause,
        isSkipped = isSkipped
    )

    private fun observe(
        text: String = "pending",
        isError: Boolean = false,
        call: ToolCallContent = call()
    ): List<Notice> = detector.observeTurn(listOf(call), listOf(result(call, text, isError)))

    private fun notice(call: ToolCallContent = call(), count: Int = 2, level: Level = Level.WARN) =
        Notice(toolName = call.name, toolCallId = call.id, count = count, level = level)
}
