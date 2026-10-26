package com.easy.easyai.core.knowledge

import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KnowledgeAccessGuardTest {

    private val articleStore = mockk<KnowledgeArticleStore>()
    private val context = KnowledgeAccessContext(userId = "alice")

    private fun article(key: String, createdBy: String?) = KnowledgeArticle(
        id = "article-$key",
        userId = "bucket",
        createdBy = createdBy,
        knowledgeKey = key,
        ossKey = "oss/$key",
        title = key,
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

    @Test
    fun `batch filtering drops rows the policy rejects`() = runBlocking {
        val mine = article("manual/mine.md", createdBy = "alice")
        val theirs = article("manual/theirs.md", createdBy = "bob")
        coEvery { articleStore.findByKeys("bucket", listOf(mine.knowledgeKey, theirs.knowledgeKey)) } returns
            listOf(mine, theirs)
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        val readable = guard.filterReadableEntries(
            context,
            "bucket",
            listOf(mine.knowledgeKey, theirs.knowledgeKey),
        )

        assertEquals(setOf(mine.knowledgeKey), readable)
    }

    @Test
    fun `an entry without metadata falls back to the policy default`() = runBlocking {
        coEvery { articleStore.findByKey("bucket", "manual/orphan.md") } returns null
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertTrue(guard.canReadEntry(context, "bucket", "manual/orphan.md"))
    }

    @Test
    fun `an entry with metadata is judged by that row`() = runBlocking {
        val theirs = article("manual/theirs.md", createdBy = "bob")
        coEvery { articleStore.findByKey("bucket", theirs.knowledgeKey) } returns theirs
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertFalse(guard.canReadEntry(context, "bucket", theirs.knowledgeKey))
    }
}
