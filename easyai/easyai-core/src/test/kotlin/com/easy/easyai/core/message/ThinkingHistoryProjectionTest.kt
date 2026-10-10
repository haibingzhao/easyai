package com.easy.easyai.core.message

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.StopReason
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ThinkingContent
import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.model.UserMessage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ThinkingHistoryProjectionTest {

    private fun transcript(): List<EasyAiMessage> = listOf(
        UserMessage("q1"),
        AssistantMessage(
            id = "asst1",
            content = listOf(
                ThinkingContent("reasoning one", thinkingSignature = "sig1"),
                TextContent("answer one"),
                ToolCallContent("tc1", "read", """{"path":"/tmp/a"}""")
            ),
            stopReason = StopReason.TOOL_USE
        ),
        AssistantMessage(
            id = "asst2",
            content = listOf(ThinkingContent("reasoning two"), TextContent("answer two")),
            stopReason = StopReason.STOP
        )
    )

    @Nested
    inner class `enabled` {

        @Test
        fun `returns the identical list untouched`() {
            val messages = transcript()
            assertSame(messages, ThinkingHistoryProjection.project(messages, enabled = true))
        }
    }

    @Nested
    inner class `disabled` {

        @Test
        fun `drops thinking blocks keeping message count order and ids`() {
            val result = ThinkingHistoryProjection.project(transcript(), enabled = false)

            assertEquals(3, result.size)
            val first = result[1] as AssistantMessage
            assertEquals("asst1", first.id)
            assertEquals(listOf("answer one"), first.content.filterIsInstance<TextContent>().map { it.text })
            assertEquals(1, first.content.filterIsInstance<ToolCallContent>().size)
            val second = result[2] as AssistantMessage
            assertEquals(listOf("answer two"), second.content.filterIsInstance<TextContent>().map { it.text })
        }

        @Test
        fun `other messages pass through untouched`() {
            val messages = transcript()
            val result = ThinkingHistoryProjection.project(messages, enabled = false)
            assertSame(messages[0], result[0])
        }

        @Test
        fun `thinking-free transcript is returned unchanged`() {
            val messages = listOf(
                UserMessage("only a question"),
                AssistantMessage(id = "a", content = listOf(TextContent("plain")), stopReason = StopReason.STOP)
            )
            val result = ThinkingHistoryProjection.project(messages, enabled = false)
            assertSame(messages[1], result[1])
            assertTrue(result.isNotEmpty())
        }
    }
}
