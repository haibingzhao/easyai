package com.easy.easyai.web.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.ToolResultContent
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.web.service.AccessibleKnowledgeArticle
import com.easy.easyai.web.service.ArticleWriteResult
import com.easy.easyai.web.service.KnowledgeArticleService
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgeArticleToolTest {

    private val service = mockk<KnowledgeArticleService>(relaxed = true)
    private val accessContext = KnowledgeAccessContext(userId = "alice", groupUserId = "g_1")

    private fun article(
        id: String = "article-1",
        title: String = "A",
        status: ArticleStatus = ArticleStatus.PUBLISHED,
        ragEnabled: Boolean = false,
        folderId: String = "folder-1",
    ) = KnowledgeArticle(
        id = id,
        userId = "g_1",
        createdBy = "alice",
        folderId = folderId,
        fileName = null,
        title = title,
        description = null,
        category = "other",
        ext = "md",
        origin = "assistant",
        status = status,
        ragEnabled = ragEnabled,
        createdAt = 1L,
        updatedAt = 2L,
    )

    private fun accessible(
        article: KnowledgeArticle,
        folderPath: String = "",
        canWrite: Boolean = true,
        canManage: Boolean = true,
    ) = AccessibleKnowledgeArticle(article, folderPath, canWrite, canManage)

    private suspend fun CoroutineScope.run(args: Map<String, Any?>): Pair<Boolean, String> {
        val result = KnowledgeArticleTool(
            ToolMetadata(name = "knowledge_article", description = "test"),
            service,
            accessContext,
        ).execute(
            agentContext = AgentContext(agentId = "test-agent", userId = "alice"),
            toolCallId = "tc-test",
            messageId = null,
            args = args,
            coroutineScope = this,
            onUpdate = {},
        )
        val output = result.content.joinToString("\n") { part ->
            when (part) {
                is TextContent -> part.text
                is ToolResultContent -> part.output
                else -> ""
            }
        }
        return result.isError to output
    }

    @Test
    fun `list returns readable articles with id, path and permission flags`() = runTest {
        coEvery { service.listAccessible(accessContext, null, null) } returns listOf(
            accessible(article(), folderPath = "Family/Recipes", canWrite = true, canManage = false)
        )

        val (isError, output) = run(mapOf("action" to "list"))

        assertEquals(false, isError)
        assertContains(output, "\"id\":\"article-1\"")
        assertContains(output, "\"path\":\"Family/Recipes\"")
        assertContains(output, "\"canWrite\":true")
        assertContains(output, "\"canManage\":false")
    }

    @Test
    fun `list applies limit and offset and reports totals`() = runTest {
        val articles = (1..5).map { index -> accessible(article(id = "article-$index")) }
        coEvery { service.listAccessible(accessContext, null, null) } returns articles

        val (isError, output) = run(mapOf("action" to "list", "limit" to 2, "offset" to 1))

        assertEquals(false, isError)
        assertContains(output, "\"total\":5")
        assertContains(output, "\"offset\":1")
        assertContains(output, "\"limit\":2")
        assertContains(output, "article-2")
        assertContains(output, "article-3")
        assertFalse(output.contains("article-1"))
        assertFalse(output.contains("article-4"))
    }

    @Test
    fun `list narrows to a folder path subtree`() = runTest {
        coEvery { service.listAccessible(accessContext, null, null) } returns listOf(
            accessible(article(id = "in"), folderPath = "Family/Recipes"),
            accessible(article(id = "out"), folderPath = "Work"),
        )

        val (isError, output) = run(mapOf("action" to "list", "folderPath" to "Family"))

        assertEquals(false, isError)
        assertContains(output, "\"total\":1")
        assertContains(output, "in")
        assertFalse(output.contains("\"id\":\"out\""))
    }

    @Test
    fun `read delegates to the access-checked service by id`() = runTest {
        coEvery { service.readContent(accessContext, "article-1") } returns "body"

        val (isError, output) = run(mapOf("action" to "read", "id" to "article-1"))

        assertEquals(false, isError)
        assertContains(output, "\"content\":\"body\"")
    }

    @Test
    fun `create stamps assistant origin and defaults to a draft`() = runTest {
        coEvery {
            service.saveArticle(accessContext, "A", "body", null, "other", "assistant", any(), any(), any(), any(), any(), any(), any())
        } returns article()
        coEvery { service.folderPathOf(any()) } returns "Family/Recipes"

        val (isError, output) = run(mapOf("action" to "create", "title" to "A", "content" to "body"))

        assertEquals(false, isError)
        assertContains(output, "\"created\":true")
        assertContains(output, "\"path\":\"Family/Recipes\"")
    }

    @Test
    fun `create rejects an unknown folder path`() = runTest {
        coEvery { service.resolveFolderIdByPath(accessContext, "Nope") } returns null

        val (isError, output) = run(
            mapOf("action" to "create", "title" to "A", "content" to "body", "folderPath" to "Nope")
        )

        assertTrue(isError)
        assertContains(output, "Folder not found")
    }

    @Test
    fun `list reports each article status`() = runTest {
        coEvery { service.listAccessible(accessContext, null, null) } returns listOf(
            accessible(article(status = ArticleStatus.DRAFT))
        )

        val (isError, output) = run(mapOf("action" to "list"))

        assertEquals(false, isError)
        assertContains(output, "\"status\":\"draft\"")
    }

    @Test
    fun `list rejects an unknown status filter instead of listing everything`() = runTest {
        val (isError, output) = run(mapOf("action" to "list", "status" to "draftt"))

        assertTrue(isError)
        assertContains(output, "Invalid status filter")
    }

    @Test
    fun `update reports a pending index write`() = runTest {
        coEvery {
            service.updateArticle(accessContext, "article-1", "body", null, null)
        } returns ArticleWriteResult(article(), indexPending = true)
        coEvery { service.folderPathOf(any()) } returns ""

        val (isError, output) = run(
            mapOf("action" to "update", "id" to "article-1", "content" to "body")
        )

        assertEquals(false, isError)
        assertContains(output, "\"indexPending\":true")
    }

    @Test
    fun `delete reports the policy outcome`() = runTest {
        coEvery { service.deleteArticle(accessContext, "article-1") } returns false

        val (isError, output) = run(mapOf("action" to "delete", "id" to "article-1"))

        assertEquals(false, isError)
        assertContains(output, "\"deleted\":false")
    }

    @Test
    fun `missing action-specific id returns an error result`() = runTest {
        val (isError, output) = run(mapOf("action" to "read"))

        assertTrue(isError)
        assertContains(output, "read requires 'id'")
    }

    @Test
    fun `unknown action lists the supported verbs`() = runTest {
        val (isError, output) = run(mapOf("action" to "rename"))

        assertTrue(isError)
        assertContains(output, "expected list/read/create/update/delete")
    }

    @Test
    fun `service denial becomes an error result instead of a crash`() = runTest {
        coEvery { service.readContent(accessContext, "article-1") } throws
            ResponseStatusException(HttpStatus.FORBIDDEN, "No read permission")

        val (isError, output) = run(mapOf("action" to "read", "id" to "article-1"))

        assertTrue(isError)
        assertContains(output, "Operation failed")
    }
}
