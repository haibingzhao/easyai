package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelOptions
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.FileRefContent
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.storage.StoredFileReference
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.LastMessageConfig
import com.easy.easyai.repository.session.MessageWithTimestamp
import com.easy.easyai.repository.session.SessionCursor
import com.easy.easyai.repository.session.SessionMeta
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
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
    private val configStore = mockk<ModelProviderConfigStore>()
    private val serviceWithConfig = SessionService(manager, store, fileStorageService = files, configStore = configStore)
    private val aliceOwners = listOf("alice", "system")
    private val bobOwners = listOf("bob", "system")
    private val groupModel = ModelProviderConfig(
        id = "cfg-1",
        name = "Family model",
        protocol = Protocol.OPENAI,
        isCustom = true,
        modelId = "m-1",
        options = ModelOptions(contextToken = 128_000),
        userId = "g_fam-1"
    )

    @BeforeEach
    fun setup() {
        coEvery { store.findSessionMeta("session-1", "alice") } returns SessionMeta(
            id = "session-1",
            projectId = null,
            createdAt = Instant.EPOCH.toEpochMilli(),
            updatedAt = Instant.EPOCH.toEpochMilli(),
        )
        coEvery { store.findSessionCursor(any(), any()) } returns null
        coEvery { store.loadMessagesWithTimestamps("session-1") } returns messages
        coEvery { store.loadMessagesWithTimestampsAfter("session-1", 0L) } returns messages
        coEvery { store.findContentUpdatedAt("session-1", "alice") } returns 200L
        coEvery { store.getLastMessageConfig("session-1") } returns null
        coEvery { files.resolveImageUrl(reference, "alice", aliceOwners) } returnsMany listOf(
            "https://example.test/image?signature=one", "https://example.test/image?signature=two"
        )
    }

    @Test
    fun `full and incremental responses refresh deduplicated URLs without changing content`() = runTest {
        val detail = assertNotNull(service.getSessionDetail("session-1", "alice", aliceOwners))
        val incremental = assertNotNull(service.getSessionMessagesAfter("session-1", 0L, "alice", aliceOwners))
        detail.messages.forEach {
            assertEquals(mapOf(reference to "https://example.test/image?signature=one"), it.fileUrls)
        }
        incremental.messages.forEach {
            assertEquals(mapOf(reference to "https://example.test/image?signature=two"), it.fileUrls)
        }
        assertSame(message.content, detail.messages.first().content)
        assertEquals(reference, (message.content[1] as FileRefContent).filePath)
        coVerify(exactly = 2) { files.resolveImageUrl(reference, "alice", aliceOwners) }
    }

    @Test
    fun `single image failure leaves text history available and is deduplicated`() = runTest {
        coEvery { files.resolveImageUrl(reference, "alice", aliceOwners) } throws IllegalStateException("storage unavailable")
        val detail = assertNotNull(service.getSessionDetail("session-1", "alice", aliceOwners))
        assertEquals(2, detail.messages.size)
        assertEquals(TextContent("Look at this"), detail.messages.first().content.first())
        assertNull(detail.messages.first().fileUrls)
        coVerify(exactly = 1) { files.resolveImageUrl(reference, "alice", aliceOwners) }
    }

    @Test
    fun `does not sign inaccessible session images`() = runTest {
        coEvery { store.findSessionMeta("session-1", "bob") } returns null
        coEvery { store.findContentUpdatedAt("session-1", "bob") } returns null
        assertNull(service.getSessionDetail("session-1", "bob", bobOwners))
        assertNull(service.getSessionMessagesAfter("session-1", 0L, "bob", bobOwners))
        coVerify(exactly = 0) { files.resolveImageUrl(any(), any(), any()) }
        coVerify(exactly = 0) { store.findSessionCursor(any(), any()) }
        Unit
    }

    @Test
    fun `does not swallow request cancellation`() = runTest {
        coEvery { files.resolveImageUrl(reference, "alice", aliceOwners) } throws CancellationException("cancelled")
        assertFailsWith<CancellationException> { service.getSessionDetail("session-1", "alice", aliceOwners) }
    }

    @Test
    fun `resolves the last message config across the caller's owner set`() = runTest {
        coEvery { store.getLastMessageConfig("session-1") } returns
            LastMessageConfig(agentId = "agent-1", configId = "cfg-1", modelId = "m-1")
        coEvery { store.findModelOverrides("session-1") } returns null
        coEvery { configStore.getConfig("cfg-1", aliceOwners) } returns groupModel

        val detail = assertNotNull(serviceWithConfig.getSessionDetail("session-1", "alice", aliceOwners))

        assertEquals(128_000, detail.modelContextLength)
        coVerify(exactly = 1) { configStore.getConfig("cfg-1", aliceOwners) }
    }

    @Test
    fun `empty owner set falls back to the caller plus system`() = runTest {
        coEvery { store.getLastMessageConfig("session-1") } returns
            LastMessageConfig(agentId = "agent-1", configId = "cfg-1", modelId = "m-1")
        coEvery { store.findModelOverrides("session-1") } returns null
        coEvery { configStore.getConfig("cfg-1", setOf("alice", "system")) } returns groupModel

        val detail = assertNotNull(serviceWithConfig.getSessionDetail("session-1", "alice", emptyList()))

        assertEquals(128_000, detail.modelContextLength)
        coVerify(exactly = 1) { configStore.getConfig("cfg-1", setOf("alice", "system")) }
    }

    @Nested
    inner class `session cursors and titles` {
        @Test
        fun `detail and seq incremental reads pass the caller to the cursor lookup`() = runTest {
            coEvery { store.findSessionCursor("session-1", "alice") } returns SessionCursor(12L, 3L)
            coEvery { store.loadMessagesAfter("session-1", 12L) } returns emptyList()
            val detail = assertNotNull(service.getSessionDetail("session-1", "alice", aliceOwners))
            val incremental = assertNotNull(service.getSessionMessages("session-1", "alice", aliceOwners, afterSeq = 12L))

            assertEquals(12L, detail.newestMessageSeq)
            assertEquals(3L, detail.contentRevision)
            assertEquals(12L, incremental.newestSeq)
            assertEquals(3L, incremental.revision)
            coVerify(exactly = 2) { store.findSessionCursor("session-1", "alice") }
            coVerify(exactly = 0) { store.findSessionCursor("session-1", "system") }
            Unit
        }

        @Test
        fun `title joins only first user text blocks without trimming and truncates to fifty characters`() = runTest {
            val prefix = "  first "
            val suffix = "x".repeat(60)
            val firstUser = UserMessage(content = listOf(
                TextContent(prefix), FileRefContent(reference, "note.txt", "text/plain", displayOffset = prefix.length), TextContent(suffix)
            ))
            coEvery { store.loadMessagesWithTimestamps("session-1") } returns listOf(
                MessageWithTimestamp(AssistantMessage(content = listOf(TextContent("not the title"))), 1L),
                MessageWithTimestamp(firstUser, 2L),
                MessageWithTimestamp(message, 3L)
            )
            val detail = assertNotNull(service.getSessionDetail("session-1", "alice", aliceOwners))
            assertEquals((prefix + suffix).take(50), detail.title)
            Unit
        }

        @Test
        fun `blank first user title does not fall through to a later user`() = runTest {
            coEvery { store.loadMessagesWithTimestamps("session-1") } returns listOf(
                MessageWithTimestamp(UserMessage(content = listOf(TextContent("  \n"))), 1L),
                MessageWithTimestamp(message, 2L)
            )
            assertNull(assertNotNull(service.getSessionDetail("session-1", "alice", aliceOwners)).title)
            Unit
        }

        @Test
        fun `missing user message leaves the title null`() = runTest {
            coEvery { store.loadMessagesWithTimestamps("session-1") } returns listOf(
                MessageWithTimestamp(AssistantMessage(content = listOf(TextContent("assistant only"))), 1L)
            )
            assertNull(assertNotNull(service.getSessionDetail("session-1", "alice", aliceOwners)).title)
            Unit
        }
    }
}
