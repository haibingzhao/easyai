package com.easy.easyai.web.service

import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.model.FileRefContent
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.storage.StoredFileReference
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.MessageWithTimestamp
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

class SessionServiceImageTest {
    private val manager = mockk<SessionManager>(relaxed = true)
    private val store = mockk<AsyncSessionStore>(relaxed = true)
    private val files = mockk<FileStorageService>()
    private val reference = StoredFileReference.create("alice", "session-1", "png")
    private val message = UserMessage(content = listOf(
        TextContent("Look at this"), FileRefContent(reference, "shot.png", "image/png", displayOffset = 12)
    ))
    private val messages = listOf(MessageWithTimestamp(message, 100L), MessageWithTimestamp(message.copy(id = "second"), 200L))
    private val service = SessionService(manager, store, fileStorageService = files)

    @BeforeEach
    fun setup() {
        coEvery { manager.getSessionDetail("session-1", "alice") } returns PersistedSession(
            "session-1", messages.map { it.message }, createdAt = Instant.EPOCH, updatedAt = Instant.EPOCH
        )
        coEvery { store.loadMessagesWithTimestamps("session-1") } returns messages
        coEvery { store.loadMessagesWithTimestampsAfter("session-1", 0L) } returns messages
        coEvery { store.findContentUpdatedAt("session-1", "alice") } returns 200L
        coEvery { store.getLastMessageConfig("session-1") } returns null
        coEvery { files.resolveImageUrl(reference, "alice") } returnsMany listOf(
            "https://example.test/image?signature=one", "https://example.test/image?signature=two"
        )
    }

    @Test
    fun `full and incremental responses refresh deduplicated URLs without changing content`() = runTest {
        val detail = assertNotNull(service.getSessionDetail("session-1", "alice"))
        val incremental = assertNotNull(service.getSessionMessagesAfter("session-1", 0L, "alice"))
        detail.messages.forEach {
            assertEquals(mapOf(reference to "https://example.test/image?signature=one"), it.fileUrls)
        }
        incremental.messages.forEach {
            assertEquals(mapOf(reference to "https://example.test/image?signature=two"), it.fileUrls)
        }
        assertSame(message.content, detail.messages.first().content)
        assertEquals(reference, (message.content[1] as FileRefContent).filePath)
        coVerify(exactly = 2) { files.resolveImageUrl(reference, "alice") }
    }

    @Test
    fun `single image failure leaves text history available and is deduplicated`() = runTest {
        coEvery { files.resolveImageUrl(reference, "alice") } throws IllegalStateException("storage unavailable")
        val detail = assertNotNull(service.getSessionDetail("session-1", "alice"))
        assertEquals(2, detail.messages.size)
        assertEquals(TextContent("Look at this"), detail.messages.first().content.first())
        assertNull(detail.messages.first().fileUrls)
        coVerify(exactly = 1) { files.resolveImageUrl(reference, "alice") }
    }

    @Test
    fun `does not sign inaccessible session images`() = runTest {
        coEvery { manager.getSessionDetail("session-1", "bob") } returns null
        coEvery { store.findContentUpdatedAt("session-1", "bob") } returns null
        assertNull(service.getSessionDetail("session-1", "bob"))
        assertNull(service.getSessionMessagesAfter("session-1", 0L, "bob"))
        coVerify(exactly = 0) { files.resolveImageUrl(any(), any()) }
    }

    @Test
    fun `does not swallow request cancellation`() = runTest {
        coEvery { files.resolveImageUrl(reference, "alice") } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.getSessionDetail("session-1", "alice") }
    }
}
