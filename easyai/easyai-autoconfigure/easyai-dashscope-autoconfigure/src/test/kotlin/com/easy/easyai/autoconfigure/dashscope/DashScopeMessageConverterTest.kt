package com.easy.easyai.autoconfigure.dashscope

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import com.easy.easyai.api.llm.AssistantMessage
import com.easy.easyai.api.llm.Message
import com.easy.easyai.api.llm.SystemMessage
import com.easy.easyai.api.llm.ToolResponseMessage
import com.easy.easyai.api.llm.UserMessage
import com.easy.easyai.api.llm.Media
import com.easy.easyai.api.llm.MediaSource
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tests for [DashScopeMessageConverter], the message walk that decides what Bailian
 * actually sees: one merged system message, one tool message per tool result, and attachments only
 * when the model declares vision.
 */
internal class DashScopeMessageConverterTest {

    private fun convert(
        messages: List<Message>,
        supportsVision: Boolean = false
    ) = DashScopeMessageConverter.convert(messages, supportsVision)

    @Nested
    inner class `system messages` {

        @Test
        fun `merges every system message into one at the front`() {
            val specs = convert(
                listOf(
                    UserMessage("问题"),
                    SystemMessage("规则一"),
                    SystemMessage("规则二")
                )
            )

            assertEquals(2, specs.size)
            assertEquals("system", specs.first().role)
            assertEquals("规则一\n\n规则二", specs.first().text)
        }

        @Test
        fun `omits the system message when there is none`() {
            val specs = convert(listOf(UserMessage("问题")))

            assertEquals(listOf("user"), specs.map { it.role })
        }
    }

    @Nested
    inner class `assistant and tool messages` {

        @Test
        fun `keeps tool calls for the next turn`() {
            val message = AssistantMessage(
                content = "",
                toolCalls = listOf(AssistantMessage.ToolCall("call_1", "function", "get_weather", """{"city":"Hangzhou"}"""))
            )

            val spec = convert(listOf(message)).single()

            assertEquals("assistant", spec.role)
            assertEquals(listOf(DashScopeToolCallSpec("call_1", "get_weather", """{"city":"Hangzhou"}""")), spec.toolCalls)
        }

        @Test
        fun `splits a tool response message into one message per result`() {
            val message = ToolResponseMessage(
                responses = listOf(
                    ToolResponseMessage.ToolResponse("call_1", "get_weather", """{"temp":26}"""),
                    ToolResponseMessage.ToolResponse("call_2", "get_time", "10:00")
                )
            )

            val specs = convert(listOf(message))

            assertEquals(listOf("call_1", "call_2"), specs.map { it.toolCallId })
            assertEquals(listOf("get_weather", "get_time"), specs.map { it.toolName })
            assertEquals(listOf("""{"temp":26}""", "10:00"), specs.map { it.text })
        }
    }

    @Nested
    inner class `attachments` {

        @Test
        fun `passes a public image url to the multimodal path`() {
            val message = UserMessage(
                content = "这张图里有什么",
                media = listOf(Media("image/png", MediaSource.Url(URI.create("https://example.com/a.png"))))
            )

            val spec = convert(listOf(message), supportsVision = true).single()

            assertEquals(listOf("https://example.com/a.png"), spec.images)
        }

        @Test
        fun `inlines binary attachments as a data url`() {
            val message = UserMessage(
                content = "看这个",
                media = listOf(Media("image/png", MediaSource.Bytes("bytes".toByteArray())))
            )

            val images = convert(listOf(message), supportsVision = true).single().images

            assertTrue(images.single().startsWith("data:image/png;base64,"))
        }

        @Test
        fun `drops attachments when the model has no vision capability`() {
            val message = UserMessage(
                content = "看这个",
                media = listOf(Media("image/png", MediaSource.Url(URI.create("https://example.com/a.png"))))
            )

            val spec = convert(listOf(message), supportsVision = false).single()

            assertEquals("看这个", spec.text)
            assertTrue(spec.images.isEmpty())
        }
    }
}
