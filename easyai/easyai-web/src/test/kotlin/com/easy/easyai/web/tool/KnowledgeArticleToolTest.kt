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
        knowledgeKey: String = "assistant/a.md",
        status: ArticleStatus = ArticleStatus.PUBLISHED,
    ) = KnowledgeArticle(
        id = id,
        userId = "g_1",
        createdBy = "alice",
        knowledgeKey = knowledgeKey,
        ossKey = "oss/a.md",
        title = "A",
        description = null,
        category = "other",
        source = "assistant",
        relativePath = knowledgeKey.substringAfter('/'),
        ext = "md",
        contentHash = null,
        origin = "assistant",
        originSessionId = null,
        originMessageId = null,
        composedFrom = null,
        status = status,
        createdAt = 1L,
        updatedAt = 2L,
    )

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
    fun `list returns readable articles with permission flags`() = runTest {
        coEvery { service.listAccessible(accessContext, null, null, null) } returns listOf(
            AccessibleKnowledgeArticle(article(), canWrite = true, canManage = false)
        )

        val (isError, output) = run(mapOf("action" to "list"))

        assertEquals(false, isError)
        assertContains(output, "\"key\":\"assistant/a.md\"")
        assertContains(output, "\"canWrite\":true")
        assertContains(output, "\"canManage\":false")
    }

    @Test
    fun `list applies limit and offset and reports totals`() = runTest {
        val articles = (1..5).map { index ->
            AccessibleKnowledgeArticle(
                article(id = "article-$index", knowledgeKey = "assistant/$index.md"),
                canWrite = true,
                canManage = true,
            )
        }
        coEvery { service.listAccessible(accessContext, null, null, null) } returns articles

        val (isError, output) = run(mapOf("action" to "list", "limit" to 2, "offset" to 1))

        assertEquals(false, isError)
        assertContains(output, "\"total\":5")
        assertContains(output, "\"offset\":1")
        assertContains(output, "\"limit\":2")
        assertContains(output, "assistant/2.md")
        assertContains(output, "assistant/3.md")
        assertFalse(output.contains("assistant/1.md"))
        assertFalse(output.contains("assistant/4.md"))
    }

    @Test
    fun `read delegates to the access-checked service`() = runTest {
        coEvery { service.readContent(accessContext, "assistant/a.md") } returns "body"

        val (isError, output) = run(mapOf("action" to "read", "key" to "assistant/a.md"))

        assertEquals(false, isError)
        assertContains(output, "\"content\":\"body\"")
    }

    @Test
    fun `create stamps assistant origin and defaults`() = runTest {
        coEvery {
            service.saveArticle(
                accessContext, "A", "body", "assistant", null, "other", "assistant", null, null, null,
                ArticleStatus.DRAFT,
            )
        } returns article()

        val (isError, output) = run(mapOf("action" to "create", "title" to "A", "content" to "body"))

        assertEquals(false, isError)
        assertContains(output, "\"created\":true")
    }

    @Test
    fun `list reports each article status`() = runTest {
        coEvery { service.listAccessible(accessContext, null, null, null) } returns listOf(
            AccessibleKnowledgeArticle(article(status = ArticleStatus.DRAFT), canWrite = true, canManage = true)
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
            service.updateArticle(accessContext, "assistant/a.md", "body", null, null)
        } returns ArticleWriteResult(article(), indexPending = true)

        val (isError, output) = run(
            mapOf("action" to "update", "key" to "assistant/a.md", "content" to "body")
        )

        assertEquals(false, isError)
        assertContains(output, "\"indexPending\":true")
    }

    @Test
    fun `delete reports the policy outcome`() = runTest {
        coEvery { service.deleteArticle(accessContext, "assistant/a.md") } returns false

        val (isError, output) = run(mapOf("action" to "delete", "key" to "assistant/a.md"))

        assertEquals(false, isError)
        assertContains(output, "\"deleted\":false")
    }

    @Test
    fun `missing action-specific key returns an error result`() = runTest {
        val (isError, output) = run(mapOf("action" to "read"))

        assertTrue(isError)
        assertContains(output, "read requires 'key'")
    }

    @Test
    fun `unknown action lists the supported verbs`() = runTest {
        val (isError, output) = run(mapOf("action" to "rename"))

        assertTrue(isError)
        assertContains(output, "expected list/read/create/update/delete")
    }

    @Test
    fun `service denial becomes an error result instead of a crash`() = runTest {
        coEvery { service.readContent(accessContext, "assistant/a.md") } throws
            ResponseStatusException(HttpStatus.FORBIDDEN, "No read permission")

        val (isError, output) = run(mapOf("action" to "read", "key" to "assistant/a.md"))

        assertTrue(isError)
        assertContains(output, "Operation failed")
    }
}
