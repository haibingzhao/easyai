package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.DefaultKnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeEntry
import com.easy.easyai.core.knowledge.KnowledgeFolder
import com.easy.easyai.core.knowledge.KnowledgeFolderStore
import com.easy.easyai.core.knowledge.KnowledgeStore
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
 * Tests for [KnowledgeSearchTool] and [KnowledgeReadTool] using a fake [KnowledgeStore] plus
 * faked article/folder metadata stores, the same combination the builder wires at runtime.
 */
class KnowledgeToolsTest {

    /** Recording fake: captures userId/query/topK/id and serves canned entries. */
    private class FakeKnowledgeStore(
        val entries: List<KnowledgeEntry> = emptyList(),
        val contents: Map<String, String> = emptyMap(),
    ) : KnowledgeStore {
        var lastUserId: String? = null
        var lastQuery: String? = null
        var lastTopK: Int? = null
        var lastId: String? = null

        override suspend fun search(userId: String, query: String, topK: Int): List<KnowledgeEntry> {
            lastUserId = userId
            lastQuery = query
            lastTopK = topK
            return entries
        }

        override suspend fun read(userId: String, id: String): String? {
            lastUserId = userId
            lastId = id
            return contents[id]
        }

        override suspend fun index(
            userId: String,
            id: String,
            content: String,
            title: String?,
            kcategory: String?,
            ext: String,
        ): Boolean = true

        override suspend fun delete(userId: String, id: String): Boolean = false

        override suspend fun chunkCounts(userId: String, ids: Collection<String>): Map<String, Int> = emptyMap()
    }

    private class FakeFolderStore(private val folders: List<KnowledgeFolder>) : KnowledgeFolderStore {
        override suspend fun listByBuckets(userIds: Collection<String>): List<KnowledgeFolder> =
            folders.filter { it.userId in userIds }

        override suspend fun findById(id: String): KnowledgeFolder? = folders.firstOrNull { it.id == id }
        override suspend fun findDefault(userId: String): KnowledgeFolder? =
            folders.firstOrNull { it.userId == userId && it.isDefault }

        override suspend fun save(folder: KnowledgeFolder) {}
        override suspend fun delete(id: String) {}
        override suspend fun deleteIfEmpty(id: String, subtreeIds: Collection<String>): Boolean =
            folders.none { it.id == id }
    }

    private fun guard(rows: List<KnowledgeArticle>): KnowledgeAccessGuard {
        val articleStore = mockk<KnowledgeArticleStore>()
        coEvery { articleStore.findById(any()) } answers {
            val id = firstArg<String>()
            rows.firstOrNull { it.id == id }
        }
        coEvery { articleStore.findByIds(any(), any()) } answers {
            val ids = secondArg<Collection<String>>()
            rows.filter { it.id in ids }
        }
        return KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)
    }

    private fun accessContext(userId: String = "user-1") = KnowledgeAccessContext(userId = userId)

    /** Defaults describe an indexed row: the guard only admits published, retrieval-enabled articles. */
    private fun articleRow(
        id: String,
        createdBy: String,
        folderId: String = "f-1",
        title: String = "Title of $id",
        category: String = "other",
        status: ArticleStatus = ArticleStatus.PUBLISHED,
        ragEnabled: Boolean = true,
    ) = KnowledgeArticle(
        id = id,
        userId = OWNER,
        createdBy = createdBy,
        folderId = folderId,
        title = title,
        description = null,
        category = category,
        ext = "md",
        origin = "manual",
        status = status,
        ragEnabled = ragEnabled,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun entry(id: String, content: String, kcategory: String = "other"): KnowledgeEntry = KnowledgeEntry(
        id = id,
        title = "Indexed title of $id",
        description = "Description of $id",
        kcategory = kcategory,
        ext = "md",
        content = content,
        updatedAt = null,
        chunksCount = null,
    )

    private fun folder(id: String, name: String, parentId: String? = null) = KnowledgeFolder(
        id = id,
        userId = OWNER,
        parentId = parentId,
        name = name,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun runTool(
        tool: ToolDefinition,
        args: Map<String, Any?>,
        userId: String? = "user-1",
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
        fun `formats hits with id, folder path, category and preview`() {
            val store = FakeKnowledgeStore(entries = listOf(entry("a1", "Architecture body")))
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                OWNER,
                guard(listOf(articleRow("a1", createdBy = "user-1", folderId = "f-recipes"))),
                accessContext(),
                articleStore = mockStoreOf(articleRow("a1", createdBy = "user-1", folderId = "f-recipes")),
                folderStore = FakeFolderStore(listOf(folder("f-family", "Family"), folder("f-recipes", "Recipes", "f-family"))),
            )

            val (isError, output) = runTool(tool, mapOf("query" to "architecture"))

            assertFalse(isError)
            assertTrue(output.contains("Found 1 knowledge entries"))
            assertTrue(output.contains("[id: a1] Title of a1"))
            assertTrue(output.contains("path: Family/Recipes"))
            assertTrue(output.contains("category: other"))
            assertTrue(output.contains("Architecture body"))
            assertEquals(OWNER, store.lastUserId)
            assertEquals("architecture", store.lastQuery)
            assertEquals(KnowledgeStore.DEFAULT_TOP_K, store.lastTopK)
        }

        @Test
        fun `articles owned by another creator are hidden`() {
            val mine = articleRow("a-mine", createdBy = "user-1")
            val theirs = articleRow("a-theirs", createdBy = "user-2")
            val store = FakeKnowledgeStore(entries = listOf(entry("a-mine", "Mine"), entry("a-theirs", "Theirs")))
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                OWNER,
                guard(listOf(mine, theirs)),
                accessContext(),
                articleStore = mockStoreOf(mine, theirs),
                folderStore = FakeFolderStore(listOf(folder("f-1", "Notes"))),
            )

            val (isError, output) = runTool(tool, mapOf("query" to "docs"))

            assertFalse(isError)
            assertTrue(output.contains("Found 1 knowledge entries"))
            assertTrue(output.contains("a-mine"))
            assertFalse(output.contains("a-theirs"))
        }

        @Test
        fun `a hit without a metadata row is dropped`() {
            val row = articleRow("a-known", createdBy = "user-1")
            val store = FakeKnowledgeStore(entries = listOf(entry("a-known", "Known"), entry("orphan", "Orphan")))
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                OWNER,
                guard(listOf(row)),
                accessContext(),
                articleStore = mockStoreOf(row),
                folderStore = FakeFolderStore(listOf(folder("f-1", "Notes"))),
            )

            val (_, output) = runTool(tool, mapOf("query" to "docs"))

            assertTrue(output.contains("Found 1 knowledge entries"))
            assertFalse(output.contains("orphan"))
        }

        @Test
        fun `category filter narrows the hits and over-fetches`() {
            val recipe = articleRow("a-recipe", createdBy = "user-1", category = "recipe")
            val note = articleRow("a-note", createdBy = "user-1", category = "note")
            val store = FakeKnowledgeStore(
                entries = listOf(entry("a-recipe", "Pork", kcategory = "recipe"), entry("a-note", "Idea", kcategory = "note"))
            )
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                OWNER,
                guard(listOf(recipe, note)),
                accessContext(),
                articleStore = mockStoreOf(recipe, note),
                folderStore = FakeFolderStore(listOf(folder("f-1", "Notes"))),
            )

            val (_, output) = runTool(tool, mapOf("query" to "docs", "kcategory" to "recipe"))

            assertTrue(output.contains("a-recipe"))
            assertFalse(output.contains("a-note"))
            assertEquals(KnowledgeStore.DEFAULT_TOP_K * 2, store.lastTopK)
        }

        @Test
        fun `folder filter matches the subtree of the resolved path`() {
            val inScope = articleRow("a-in", createdBy = "user-1", folderId = "f-dinner")
            val outScope = articleRow("a-out", createdBy = "user-1", folderId = "f-work")
            val store = FakeKnowledgeStore(entries = listOf(entry("a-in", "Soup"), entry("a-out", "Ledger")))
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                store,
                OWNER,
                guard(listOf(inScope, outScope)),
                accessContext(),
                articleStore = mockStoreOf(inScope, outScope),
                folderStore = FakeFolderStore(
                    listOf(
                        folder("f-family", "Family"),
                        folder("f-recipes", "Recipes", "f-family"),
                        folder("f-dinner", "Dinner", "f-recipes"),
                        folder("f-work", "Work"),
                    )
                ),
            )

            val (_, output) = runTool(tool, mapOf("query" to "docs", "folder" to "Family/Recipes"))

            assertTrue(output.contains("a-in"))
            assertFalse(output.contains("a-out"))
            assertTrue(output.contains("path: Family/Recipes/Dinner"))
        }

        @Test
        fun `an unknown folder path reports the miss`() {
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                FakeKnowledgeStore(entries = listOf(entry("a1", "Body"))),
                OWNER,
                guard(listOf(articleRow("a1", createdBy = "user-1"))),
                accessContext(),
                articleStore = mockStoreOf(articleRow("a1", createdBy = "user-1")),
                folderStore = FakeFolderStore(listOf(folder("f-1", "Notes"))),
            )

            val (isError, output) = runTool(tool, mapOf("query" to "docs", "folder" to "Nowhere"))

            assertFalse(isError)
            assertTrue(output.contains("No knowledge folder found matching 'Nowhere'"))
        }

        @Test
        fun `folder filtering without a folder store is reported, not silently ignored`() {
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                FakeKnowledgeStore(),
                OWNER,
                guard(emptyList()),
                accessContext(),
                articleStore = null,
                folderStore = null,
            )

            val (_, output) = runTool(tool, mapOf("query" to "docs", "folder" to "Family"))

            assertTrue(output.contains("Folder filtering is unavailable"))
        }

        @Test
        fun `returns friendly message when no hits`() {
            val tool = KnowledgeSearchTool(
                ToolMetadata(name = "knowledge_search", description = "test"),
                FakeKnowledgeStore(),
                OWNER,
                guard(emptyList()),
                accessContext(),
                articleStore = null,
                folderStore = null,
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
                OWNER,
                guard(emptyList()),
                accessContext(),
                articleStore = null,
                folderStore = null,
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
                OWNER,
                guard(emptyList()),
                accessContext("system"),
                articleStore = null,
                folderStore = null,
            )

            runTool(tool, mapOf("query" to "q"), userId = null)

            assertEquals(OWNER, store.lastUserId)
        }
    }

    @Nested
    inner class `knowledge_read` {

        @Test
        fun `returns full content for an accessible id`() {
            val store = FakeKnowledgeStore(contents = mapOf("a1" to "Full document"))
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                store,
                OWNER,
                guard(listOf(articleRow("a1", createdBy = "user-1"))),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("id" to "a1"))

            assertFalse(isError)
            assertEquals("Full document", output)
            assertEquals(OWNER, store.lastUserId)
            assertEquals("a1", store.lastId)
        }

        @Test
        fun `an article owned by another creator is denied as not found`() {
            val store = FakeKnowledgeStore(contents = mapOf("a-theirs" to "Secret"))
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                store,
                OWNER,
                guard(listOf(articleRow("a-theirs", createdBy = "user-2"))),
                accessContext(),
            )

            val (isError, output) = runTool(tool, mapOf("id" to "a-theirs"))

            assertTrue(isError)
            assertTrue(output.contains("Knowledge article not found: a-theirs"))
        }

        @Test
        fun `missing id returns error`() {
            val tool = KnowledgeReadTool(
                ToolMetadata(name = "knowledge_read", description = "test"),
                FakeKnowledgeStore(),
                OWNER,
                guard(emptyList()),
                accessContext(),
            )

            val (isError, output) = runTool(tool, emptyMap())

            assertTrue(isError)
            assertTrue(output.contains("'id' parameter is required"))
        }
    }

    private fun mockStoreOf(vararg rows: KnowledgeArticle): KnowledgeArticleStore {
        val store = mockk<KnowledgeArticleStore>()
        coEvery { store.findByIds(any(), any()) } answers {
            val ids = secondArg<Collection<String>>()
            rows.filter { it.id in ids }
        }
        return store
    }

    private companion object {
        const val OWNER = "owner-bucket"
    }
}
