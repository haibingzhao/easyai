package com.easy.easyai.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RagKnowledgeStoreTest {

    private fun detail(
        id: String,
        content: String?,
        chunksCount: Int? = null,
    ) = RagDocumentDetail(
        docId = "doc-$id",
        externalId = RagConstants.externalIdOf(id),
        filePath = RagConstants.filePathOf(id),
        content = content,
        status = "processed",
        createTime = 1L,
        chunksCount = chunksCount,
    )

    private fun chunk(id: String) = RagChunk(
        content = "chunk of $id",
        filePath = RagConstants.filePathOf(id),
        score = 0.9,
        createTime = 1L,
        metadata = emptyMap(),
        docId = "doc-$id",
    )

    /** Wraps [body] exactly the way the store does, so parsing round-trips. */
    private fun frontmatter(title: String, ext: String, category: String, body: String) = buildString {
        appendLine("---")
        appendLine("title: $title")
        appendLine("description: \"\"")
        appendLine("kcategory: $category")
        appendLine("ext: $ext")
        appendLine("updated: 1700000000000")
        appendLine("---")
        appendLine()
        append(body)
    }

    @Nested
    inner class Search {

        @Test
        fun `chunks are deduplicated by article id`() = runTest {
            val client = mockk<RagClient>()
            coEvery { client.search(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                listOf(chunk("a1"), chunk("a1"), chunk("a2"))
            coEvery { client.readByExternalId(RagConstants.externalIdOf("a1"), any()) } returns
                detail("a1", frontmatter("First", "md", "recipe", "First body"))
            coEvery { client.readByExternalId(RagConstants.externalIdOf("a2"), any()) } returns
                detail("a2", frontmatter("Second", "kt", "note", "Second body"), chunksCount = 3)
            val store = RagKnowledgeStore(client)

            val entries = store.search("alice", "dinner", topK = 8)

            assertEquals(listOf("a1", "a2"), entries.map { it.id })
            assertEquals("First", entries[0].title)
            assertEquals("recipe", entries[0].kcategory)
            assertEquals("md", entries[0].ext)
            assertEquals("First body", entries[0].content)
            assertEquals("kt", entries[1].ext)
            assertEquals(3, entries[1].chunksCount)
        }

        @Test
        fun `a document that fails to read does not abort the search`() = runTest {
            val client = mockk<RagClient>()
            coEvery { client.search(any(), any(), any(), any(), any(), any(), any(), any()) } returns
                listOf(chunk("bad"), chunk("good"))
            coEvery { client.readByExternalId(RagConstants.externalIdOf("bad"), any()) } throws
                RagException("boom")
            coEvery { client.readByExternalId(RagConstants.externalIdOf("good"), any()) } returns
                detail("good", frontmatter("Good", "md", "other", "Good body"))
            val store = RagKnowledgeStore(client)

            val entries = store.search("alice", "dinner")

            assertEquals(listOf("good"), entries.map { it.id })
        }

        @Test
        fun `no chunks yields no entries`() = runTest {
            val client = mockk<RagClient>()
            coEvery { client.search(any(), any(), any(), any(), any(), any(), any(), any()) } returns emptyList()
            val store = RagKnowledgeStore(client)

            assertTrue(store.search("alice", "dinner").isEmpty())
            coVerify(exactly = 0) { client.readByExternalId(any(), any()) }
        }
    }

    @Nested
    inner class Read {

        @Test
        fun `returns the body without the frontmatter`() = runTest {
            val client = mockk<RagClient>()
            coEvery { client.readByExternalId(RagConstants.externalIdOf("a1"), any()) } returns
                detail("a1", frontmatter("Title", "md", "other", "# Title\n\nBody"))
            val store = RagKnowledgeStore(client)

            assertEquals("# Title\n\nBody", store.read("alice", "a1"))
        }

        @Test
        fun `a missing entry reads as null`() = runTest {
            val client = mockk<RagClient>()
            coEvery { client.readByExternalId(any(), any()) } returns null
            val store = RagKnowledgeStore(client)

            assertEquals(null, store.read("alice", "missing"))
        }
    }

    @Nested
    inner class Index {

        @Test
        fun `markdown is upserted with structure-aware options and frontmatter`() = runTest {
            val client = mockk<RagClient>(relaxed = true)
            val docSlot = slot<RagDocument>()
            coEvery { client.upsert(capture(docSlot), any(), false) } returns RagUpsertResult(docId = "d1", indexed = false)
            val store = RagKnowledgeStore(client)

            assertTrue(store.index("alice", "a1", "# A\n\nbody", "A", "recipe", "md"))

            val doc = docSlot.captured
            assertEquals("a1", doc.key)
            assertTrue(doc.content.startsWith("---"))
            assertTrue(doc.content.contains("kcategory: recipe"))
            assertTrue(doc.content.contains("ext: md"))
            assertTrue(doc.content.endsWith("body"))
            assertEquals(mapOf("kcategory" to "recipe", "ext" to "md", "title" to "A"), doc.metadata)
            assertEquals("structure_aware", doc.options.chunkMethod)
            assertEquals(true, doc.options.buildStructure)
        }

        @Test
        fun `an unsupported extension is rejected without touching the index`() = runTest {
            val client = mockk<RagClient>(relaxed = true)
            val store = RagKnowledgeStore(client)

            assertFalse(store.supports("pdf"))
            assertFalse(store.index("alice", "a1", "binary", "A", "other", "pdf"))
            coVerify(exactly = 0) { client.upsert(any(), any(), any()) }
        }

        @Test
        fun `a plain-text file is accepted with default options`() = runTest {
            val client = mockk<RagClient>(relaxed = true)
            val docSlot = slot<RagDocument>()
            coEvery { client.upsert(capture(docSlot), any(), false) } returns RagUpsertResult(docId = "d1", indexed = false)
            val store = RagKnowledgeStore(client)

            assertTrue(store.supports("kt"))
            assertTrue(store.index("alice", "a1", "fun main() {}", "Main", null, "kt"))

            assertEquals(null, docSlot.captured.options.chunkMethod)
            assertTrue(docSlot.captured.content.contains("kcategory: other"))
        }

        @Test
        fun `an index failure is reported, not thrown`() = runTest {
            val client = mockk<RagClient>()
            coEvery { client.upsert(any(), any(), false) } throws RagException("rag down")
            val store = RagKnowledgeStore(client)

            assertFalse(store.index("alice", "a1", "body", "A", "other", "md"))
        }
    }

    @Nested
    inner class DeleteAndChunkCounts {

        @Test
        fun `delete removes an existing entry`() = runTest {
            val client = mockk<RagClient>(relaxed = true)
            coEvery { client.readByExternalId(RagConstants.externalIdOf("a1"), any()) } returns detail("a1", "body")
            coEvery { client.delete(RagConstants.externalIdOf("a1"), any()) } returns true
            val store = RagKnowledgeStore(client)

            assertTrue(store.delete("alice", "a1"))
            coVerify(exactly = 1) { client.delete(RagConstants.externalIdOf("a1"), any()) }
        }

        @Test
        fun `delete is a no-op when the entry is missing`() = runTest {
            val client = mockk<RagClient>(relaxed = true)
            coEvery { client.readByExternalId(any(), any()) } returns null
            val store = RagKnowledgeStore(client)

            assertFalse(store.delete("alice", "missing"))
            coVerify(exactly = 0) { client.delete(any(), any()) }
        }

        @Test
        fun `chunkCounts reports only ids that finished indexing`() = runTest {
            val client = mockk<RagClient>()
            coEvery { client.readByExternalId(RagConstants.externalIdOf("done"), any()) } returns
                detail("done", "body", chunksCount = 4)
            coEvery { client.readByExternalId(RagConstants.externalIdOf("pending"), any()) } returns
                detail("pending", "body", chunksCount = null)
            coEvery { client.readByExternalId(RagConstants.externalIdOf("gone"), any()) } returns null
            val store = RagKnowledgeStore(client)

            val counts = store.chunkCounts("alice", listOf("done", "pending", "gone"))

            assertEquals(mapOf("done" to 4), counts)
        }

        @Test
        fun `chunkCounts of an empty request does not call the index`() = runTest {
            val client = mockk<RagClient>(relaxed = true)
            val store = RagKnowledgeStore(client)

            assertTrue(store.chunkCounts("alice", emptyList()).isEmpty())
            coVerify(exactly = 0) { client.readByExternalId(any(), any()) }
        }
    }
}
