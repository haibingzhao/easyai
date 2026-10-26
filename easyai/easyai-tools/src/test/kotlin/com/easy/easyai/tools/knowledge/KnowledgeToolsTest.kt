package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.knowledge.DefaultKnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeDetail
import com.easy.easyai.core.knowledge.KnowledgeEntry
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeUploadItem
import com.easy.easyai.core.knowledge.UploadResult
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolUpdate
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Tests for [KnowledgeSearchTool] and [KnowledgeReadTool] using a fake [KnowledgeStore].
 */
class KnowledgeToolsTest {

    /** Recording fake: captures userId/query/key and serves canned entries. */
    private class FakeKnowledgeStore(
        val entries: List<KnowledgeEntry> = emptyList(),
        val contents: Map<String, String> = emptyMap()
    ) : KnowledgeStore {
        var lastUserId: String? = null
        var lastQuery: String? = null
        var lastKey: String? = null

        override suspend fun uploadBatch(
            userId: String,
            source: String,
            items: List<KnowledgeUploadItem>,
            kcategory: String?
        ): List<UploadResult> = emptyList()

        override suspend fun list(
            userId: String,
            source: String?,
            kcategory: String?,
            query: String?
        ): List<KnowledgeEntry> {
            lastUserId = userId
            lastQuery = query
            return entries
        }

        override suspend fun detail(userId: String, key: String): KnowledgeDetail? = null

        override suspend fun read(userId: String, key: String): String? {
            lastUserId = userId
            lastKey = key
            return contents[key]
        }

        override suspend fun delete(userId: String, key: String): Boolean = false

        override suspend fun deleteSource(userId: String, source: String): Int = 0

        override suspend fun sources(userId: String): List<String> = emptyList()
    }

    private fun guard(rows: List<KnowledgeArticle> = emptyList()): KnowledgeAccessGuard {
        val articleStore = mockk<KnowledgeArticleStore>()
        coEvery { articleStore.findByKeys(any(), any()) } answers {
            val keys = secondArg<Collection<String>>()
            rows.filter { it.knowledgeKey in keys }
        }
        coEvery { articleStore.findByKey(any(), any()) } answers {
            val key = secondArg<String>()
            rows.firstOrNull { it.knowledgeKey == key }
        }
        return KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)
    }

    private fun accessContext(userId: String = "user-1") = KnowledgeAccessContext(userId = userId)

    private fun articleRow(key: String, createdBy: String) = KnowledgeArticle(
        id = "article-$key",
        userId = "owner-bucket",
        createdBy = createdBy,
        knowledgeKey = key,
        ossKey = "oss/$key",
        title = "Title",
        description = null,
        category = "other",
        source = key.substringBefore('/'),
        relativePath = key.substringAfter('/'),
        ext = "md",
        contentHash = null,
        origin = "manual",
        originSessionId = null,
        originMessageId = null,
        composedFrom = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun entry(key: String, content: String): KnowledgeEntry = KnowledgeEntry(
        key = key,
        source = key.substringBefore('/'),
        relativePath = key.substringAfter('/'),
        title = "Title of $key",
        description = "Description of $key",
        kcategory = "other",
        ext = "md",
        content = content,
        updatedAt = null,
        chunksCount = null
    )

    private fun runTool(
        tool: ToolDefinition,
        args: Map<String, Any?>,
        userId: String? = "user-1"
    ) = runBlocking {
        val result = tool.execute(
            agentContext = AgentContext(agentId = "test-agent", userId = userId),
            toolCallId = "tc-test",
            messageId = null,
            args = args,
            coroutineScope = this,
            onUpdate = { _: ToolUpdate -> }
        )
        val output = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        result.isError to output
    }

    @Nested
    inner class `knowledge_search` {

        @Test
        fun `formats hits with key, title, description and content preview`() {
            val store = FakeKnowledgeStore(entries = listOf(entry("docs/arch.md", "Architecture body")))
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                "user-1",
                guard(),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("query" to "architecture"))

            assertFalse(isError)
            assertTrue(output.contains("Found 1 knowledge entries"))
            assertTrue(output.contains("[docs/arch.md] Title of docs/arch.md — Description of docs/arch.md"))
            assertTrue(output.contains("Architecture body"))
            assertEquals("user-1", store.lastUserId)
            assertEquals("architecture", store.lastQuery)
        }

        @Test
        fun `entries owned by another creator are hidden`() {
            val store = FakeKnowledgeStore(
                entries = listOf(entry("docs/mine.md", "Mine"), entry("docs/theirs.md", "Theirs"))
            )
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                "user-1",
                guard(listOf(articleRow("docs/theirs.md", createdBy = "user-2"))),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("query" to "docs"))

            assertFalse(isError)
            assertTrue(output.contains("Found 1 knowledge entries"))
            assertTrue(output.contains("docs/mine.md"))
            assertFalse(output.contains("docs/theirs.md"))
        }

        @Test
        fun `returns friendly message when no hits`() {
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                FakeKnowledgeStore(),
                "user-1",
                guard(),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("query" to "nothing"))

            assertFalse(isError)
            assertTrue(output.contains("No knowledge entries found matching 'nothing'"))
        }

        @Test
        fun `missing query returns error`() {
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                FakeKnowledgeStore(),
                "user-1",
                guard(),
                accessContext(),
            )
            val (isError, output) = runTool(tool, emptyMap())

            assertTrue(isError)
            assertTrue(output.contains("'query' parameter is required"))
        }

        @Test
        fun `uses the baked knowledge owner regardless of the context user`() {
            val store = FakeKnowledgeStore()
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                "system",
                guard(),
                accessContext("system"),
            )

            runTool(tool, mapOf("query" to "q"), userId = null)

            assertEquals("system", store.lastUserId)
        }
    }

    @Nested
    inner class `knowledge_read` {

        @Test
        fun `returns full content for existing key`() {
            val store = FakeKnowledgeStore(contents = mapOf("docs/arch.md" to "Full document"))
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                store,
                "user-1",
                guard(),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("key" to "docs/arch.md"))

            assertFalse(isError)
            assertEquals("Full document", output)
            assertEquals("user-1", store.lastUserId)
        }

        @Test
        fun `entry owned by another creator is denied as not found`() {
            val store = FakeKnowledgeStore(contents = mapOf("docs/theirs.md" to "Secret"))
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                store,
                "user-1",
                guard(listOf(articleRow("docs/theirs.md", createdBy = "user-2"))),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("key" to "docs/theirs.md"))

            assertTrue(isError)
            assertTrue(output.contains("Knowledge entry not found: docs/theirs.md"))
        }

        @Test
        fun `the requested key decides access when several rows exist`() {
            val store = FakeKnowledgeStore(
                contents = mapOf("docs/mine.md" to "Mine", "docs/theirs.md" to "Theirs")
            )
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                store,
                "user-1",
                guard(
                    listOf(
                        articleRow("docs/mine.md", createdBy = "user-1"),
                        articleRow("docs/theirs.md", createdBy = "user-2"),
                    )
                ),
                accessContext(),
            )

            val (mineError, mineOutput) = runTool(tool, mapOf("key" to "docs/mine.md"))
            assertFalse(mineError)
            assertEquals("Mine", mineOutput)

            val (theirsError, theirsOutput) = runTool(tool, mapOf("key" to "docs/theirs.md"))
            assertTrue(theirsError)
            assertTrue(theirsOutput.contains("Knowledge entry not found: docs/theirs.md"))
        }

        @Test
        fun `missing entry returns error`() {
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                FakeKnowledgeStore(),
                "user-1",
                guard(),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("key" to "missing/doc.md"))

            assertTrue(isError)
            assertTrue(output.contains("Knowledge entry not found: missing/doc.md"))
        }

        @Test
        fun `missing key returns error`() {
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                FakeKnowledgeStore(),
                "user-1",
                guard(),
                accessContext(),
            )

            val (isError, output) = runTool(tool, emptyMap())

            assertTrue(isError)
            assertTrue(output.contains("'key' parameter is required"))
        }
    }
}
