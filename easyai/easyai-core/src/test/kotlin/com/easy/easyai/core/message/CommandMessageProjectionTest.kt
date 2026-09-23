package com.easy.easyai.core.message

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.FileRefContent
import com.easy.easyai.core.model.FolderRefContent
import com.easy.easyai.core.model.ImageContent
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class CommandMessageProjectionTest {

    @Nested
    inner class Snapshots {

        @Test
        fun `inserts adjacent expansion without changing original content metadata or usage`() {
            val command = UserMessage(
                content = listOf(
                    TextContent("/review attached"),
                    ImageContent(byteArrayOf(1, 2), "image/png"),
                    FileRefContent("/project/main.kt", "main.kt", "text/plain", displayOffset = 8),
                    FolderRefContent("/project/src", "src", 8)
                ),
                metadata = mapOf(
                    UserMessage.COMMAND_EXPANSION to "Review the captured source snapshot.",
                    UserMessage.COMMAND_NAME to "review",
                    UserMessage.COMMAND_SOURCE to "user",
                    UserMessage.COMMAND_CATEGORY to "command",
                    UserMessage.COMMAND_USER_ID to "alice",
                    UserMessage.COMMAND_PROJECT_PATH to "/project",
                    "unrelated" to "preserved"
                ),
                usage = Usage(inputTokens = 42)
            )
            val answer = AssistantMessage(content = listOf(TextContent("Earlier answer")))
            val transcript = mutableListOf<EasyAiMessage>(UserMessage("Earlier question"), answer, command)
            val before = transcript.toList()

            val first = CommandMessageProjection.project(transcript)
            val second = CommandMessageProjection.project(transcript)
            assertEquals(before, transcript)
            assertNotSame(transcript, first)
            assertNotSame(first, second)
            assertEquals(first, second)
            assertEquals(4, first.size)
            assertSame(answer, first[1])
            assertEquals(command.metadata[UserMessage.COMMAND_EXPANSION], assertIs<SystemMessage>(first[2]).text)
            assertSame(command, first[3])
            assertSame(command.content, (first[3] as UserMessage).content)
            assertSame(command.metadata, (first[3] as UserMessage).metadata)
        }

        @Test
        fun `reprojecting a snapshot does not inject twice and preserves equal distinct commands`() {
            val firstCommand = UserMessage("/same").copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to "Same expansion"))
            val secondCommand = firstCommand.copy(id = "other-command")
            val unrelatedSystem = SystemMessage(text = "Same expansion")
            val first = CommandMessageProjection.project(listOf(unrelatedSystem, firstCommand, secondCommand))
            val second = CommandMessageProjection.project(first)
            assertEquals(first, second)
            assertNotSame(first, second)
            assertEquals(5, first.size)
            assertSame(unrelatedSystem, first[0])
            assertIs<SystemMessage>(first[1])
            assertSame(firstCommand, first[2])
            assertIs<SystemMessage>(first[3])
            assertSame(secondCommand, first[4])
        }

        @Test
        fun `blank expansion and compaction summary metadata never inject instructions`() {
            val plain = UserMessage("hello")
            val blank = UserMessage("/blank").copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to " \n"))
            val summary = UserMessage("Condensed history").copy(metadata = mapOf(
                "isCompactionSummary" to "true",
                UserMessage.COMMAND_EXPANSION to "Old instructions must not return"
            ))
            val transcript = listOf(plain, blank, summary)
            val projected = CommandMessageProjection.project(transcript)
            assertNotSame(transcript, projected)
            assertEquals(transcript, projected)
            assertNotSame(emptyList<EasyAiMessage>(), CommandMessageProjection.project(emptyList()))
        }
    }
}
