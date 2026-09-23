package com.easy.easyai.core.agent

import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.FileRefContent
import com.easy.easyai.core.model.FolderRefContent
import com.easy.easyai.core.model.ImageContent
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.Usage
import com.easy.easyai.core.model.UserMessage
import io.mockk.mockk
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class PendingMessageQueueTest {

    private fun command() = UserMessage(
        content = listOf(
            TextContent("/review old"),
            ImageContent(byteArrayOf(1), "image/png"),
            FileRefContent("/project/code.kt", "code.kt", "text/plain", displayOffset = 8),
            FolderRefContent("/project/src", "src", 8)
        ),
        metadata = mapOf(
            UserMessage.COMMAND_EXPANSION to "Captured old expansion",
            UserMessage.COMMAND_NAME to "review",
            UserMessage.COMMAND_SOURCE to "user",
            UserMessage.COMMAND_CATEGORY to "command",
            UserMessage.COMMAND_USER_ID to "alice",
            UserMessage.COMMAND_PROJECT_PATH to "/project",
            "attachmentOwner" to "alice"
        ),
        usage = Usage(inputTokens = 7)
    )

    @Nested
    inner class SnapshotReplacement {

        @Test
        fun `replacement keeps queue id type order and prepared message intact`() {
            val queue = PendingMessageQueue()
            val before = UserMessage("before")
            val original = command()
            val after = UserMessage("after")
            val firstId = queue.enqueueWithType(before, "followUp")
            val id = queue.enqueueWithType(original, "steer")
            val lastId = queue.enqueueWithType(after, "followUp")
            val snapshot = assertNotNull(queue.get(id))
            val replacement = snapshot.copy(metadata = snapshot.metadata + (UserMessage.COMMAND_EXPANSION to "New snapshot"))

            assertSame(original, snapshot)
            assertTrue(queue.updateMessage(id, snapshot, replacement))
            assertSame(replacement, queue.get(id))
            assertSame(snapshot.content, queue.get(id)?.content)
            assertEquals(listOf(firstId, id, lastId), queue.peekAll().map { it.id })
            assertEquals(listOf("followUp", "steer", "followUp"), queue.peekAll().map { it.type })
            assertEquals(listOf(before, replacement, after), queue.poll(PendingMessageQueue.Mode.ALL))
            assertNull(queue.get(id))
        }

        @Test
        fun `stale same-id snapshot cannot overwrite another edit`() {
            val queue = PendingMessageQueue()
            val original = command()
            val id = queue.enqueueWithType(original, "followUp")
            val updated = original.copy(metadata = original.metadata + (UserMessage.COMMAND_EXPANSION to "Fresh"))
            assertTrue(queue.updateMessage(id, original, updated))
            assertFalse(queue.updateMessage(id, original, command()))
            assertFalse(queue.updateMessage(id, updated.copy(), command()))
            assertSame(updated, queue.get(id))
        }

        @Test
        fun `consumed or removed messages cannot be revived`() {
            for (mode in PendingMessageQueue.Mode.entries) {
                val queue = PendingMessageQueue()
                val original = command()
                val id = queue.enqueueWithType(original, "steer")
                assertEquals(listOf(original), queue.poll(mode))
                assertFalse(queue.updateMessage(id, original, command()))
                assertFalse(queue.update(id, "edited"))
                assertTrue(queue.isEmpty())
            }
            val queue = PendingMessageQueue()
            val original = command()
            val id = queue.enqueueWithType(original, "steer")
            assertTrue(queue.remove(id))
            assertFalse(queue.updateMessage(id, original, command()))
            assertNull(queue.get(id))
        }

        @Test
        fun `poll and replacement race yields exactly one complete snapshot`() {
            val executor = Executors.newFixedThreadPool(2)
            try {
                for (mode in PendingMessageQueue.Mode.entries) {
                    repeat(20) {
                        val queue = PendingMessageQueue()
                        val original = command()
                        val replacement = original.copy(metadata = mapOf(UserMessage.COMMAND_EXPANSION to "Updated"))
                        val id = queue.enqueueWithType(original, "followUp")
                        val start = CountDownLatch(1)
                        val update = executor.submit<Boolean> {
                            check(start.await(5, TimeUnit.SECONDS))
                            queue.updateMessage(id, original, replacement)
                        }
                        val poll = executor.submit<List<EasyAiMessage>> {
                            check(start.await(5, TimeUnit.SECONDS))
                            queue.poll(mode)
                        }
                        start.countDown()
                        val updated = update.get(5, TimeUnit.SECONDS)
                        assertEquals(listOf(if (updated) replacement else original), poll.get(5, TimeUnit.SECONDS))
                        assertTrue(queue.isEmpty())
                        assertFalse(queue.updateMessage(id, replacement, original))
                    }
                }
            } finally {
                executor.shutdownNow()
            }
        }

        @Test
        fun `legacy update preserves attachments usage and other metadata but drops command snapshot`() {
            val queue = PendingMessageQueue()
            val original = command()
            val id = queue.enqueueWithType(original, "followUp")
            assertTrue(queue.update(id, "ordinary edited text"))
            val updated = assertNotNull(queue.get(id))
            assertEquals(original.id, updated.id)
            assertEquals(listOf(TextContent("ordinary edited text")) + original.content.drop(1), updated.content)
            assertEquals(mapOf("attachmentOwner" to "alice"), updated.metadata)
            assertEquals(original.usage, updated.usage)
            assertFalse(queue.updateMessage(id, original, command()))
        }

        @Test
        fun `non-user messages cannot be edited and reorder cannot duplicate queue entries`() {
            val queue = PendingMessageQueue()
            val systemId = queue.enqueueWithType(SystemMessage(text = "system"), "steer")
            val userId = queue.enqueueWithType(command(), "followUp")
            assertNull(queue.get(systemId))
            assertFalse(queue.update(systemId, "edit"))
            assertFalse(queue.updateMessage(systemId, command(), command()))
            queue.reorder(listOf(userId, userId, systemId))
            assertEquals(listOf(userId, systemId), queue.peekAll().map { it.id })
            assertEquals(2, queue.poll(PendingMessageQueue.Mode.ALL).size)
            assertTrue(queue.isEmpty())
        }
    }

    @Nested
    inner class SessionFacade {

        @Test
        fun `session exposes snapshot replacement for both queues and rejects cleared entries`() {
            val session = ChatSession("session", Agent(AgentContext(agentId = "test"), mockk<AgentService>(relaxed = true)))
            val original = command()
            val steerId = session.steerWithId(original)
            val followUpId = session.followUpWithId(original)
            for (id in listOf(steerId, followUpId)) {
                val snapshot = assertNotNull(session.getQueuedMessage(id))
                val replacement = snapshot.copy(metadata = snapshot.metadata + (UserMessage.COMMAND_EXPANSION to "Prepared"))
                assertTrue(session.updateQueuedMessage(id, snapshot, replacement))
                assertSame(replacement, session.getQueuedMessage(id))
                assertFalse(session.updateQueuedMessage(id, snapshot, original))
            }
            assertEquals(listOf(steerId, followUpId), session.getQueuedMessages().map { it.id })
            val snapshot = assertNotNull(session.getQueuedMessage(followUpId))
            session.reset()
            assertNull(session.getQueuedMessage(followUpId))
            assertFalse(session.updateQueuedMessage(followUpId, snapshot, original))
            assertTrue(session.getQueuedMessages().isEmpty())
        }
    }
}
