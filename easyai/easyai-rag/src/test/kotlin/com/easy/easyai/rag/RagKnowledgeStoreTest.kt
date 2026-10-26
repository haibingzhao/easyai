package com.easy.easyai.rag

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class RagKnowledgeStoreTest {

    private fun doc(id: String, key: String) = RagDocInfo(
        docId = id,
        filePath = RagConstants.filePathOf(key),
        status = "processed",
        externalId = RagConstants.externalIdOf(key),
        contentSummary = null,
        contentLength = null,
        chunksCount = null,
        createdAt = null,
        updatedAt = null,
    )

    @Test
    fun `listKeys derives keys from document paths without reading documents`() = runTest {
        val client = mockk<RagClient>()
        coEvery { client.list(any(), any(), any()) } returns listOf(
            doc("1", "manual/a.md"),
            doc("2", "manual/b.md"),
        )
        val store = RagKnowledgeStore(client)

        val keys = store.listKeys("alice")

        assertEquals(listOf("manual/a.md", "manual/b.md"), keys)
        coVerify { client.list("${RagConstants.FILE_PATH_ROOT}/", any(), any()) }
        coVerify(exactly = 0) { client.readByExternalId(any(), any()) }
    }

    @Test
    fun `deleteEntries batch-deletes only the requested keys`() = runTest {
        val client = mockk<RagClient>()
        coEvery { client.list("${RagConstants.FILE_PATH_ROOT}/manual/", any(), any()) } returns listOf(
            doc("1", "manual/a.md"),
            doc("2", "manual/b.md"),
        )
        coEvery { client.batchDelete(any(), any()) } returns 1
        val store = RagKnowledgeStore(client)

        val deleted = store.deleteEntries("alice", listOf("manual/a.md"))

        assertEquals(1, deleted)
        coVerify { client.batchDelete(listOf("1"), any()) }
    }
}
