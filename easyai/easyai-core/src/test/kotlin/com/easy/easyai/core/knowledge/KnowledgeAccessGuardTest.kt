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

    /** Defaults describe a row that legitimately lives in the index: published and retrievable. */
    private fun article(
        id: String,
        createdBy: String?,
        userId: String = "bucket",
        status: ArticleStatus = ArticleStatus.PUBLISHED,
        ragEnabled: Boolean = true,
    ) = KnowledgeArticle(
        id = id,
        userId = userId,
        createdBy = createdBy,
        folderId = "folder-1",
        title = id,
        description = null,
        category = "other",
        ext = "md",
        origin = "manual",
        status = status,
        ragEnabled = ragEnabled,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Test
    fun `batch filtering keeps only rows the policy accepts`() = runBlocking {
        val mine = article("a-mine", createdBy = "alice")
        val theirs = article("a-theirs", createdBy = "bob")
        val foreign = article("a-foreign", createdBy = "alice", userId = "other-bucket")
        coEvery { articleStore.findByIds("bucket", listOf(mine.id, theirs.id, foreign.id)) } returns
            listOf(mine, theirs, foreign)
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        val readable = guard.filterReadableEntries(
            context,
            "bucket",
            listOf(mine.id, theirs.id, foreign.id),
        )

        assertEquals(setOf(mine.id), readable)
    }

    @Test
    fun `an id without a row is denied`() = runBlocking {
        coEvery { articleStore.findById("missing") } returns null
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertFalse(guard.canReadEntry(context, "bucket", "missing"))
    }

    @Test
    fun `a row in another bucket is denied even when the policy would allow it`() = runBlocking {
        val own = article("a1", createdBy = "alice", userId = "alice")
        coEvery { articleStore.findById(own.id) } returns own
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertFalse(guard.canReadEntry(context, "bucket", own.id))
        assertTrue(guard.canReadEntry(context, "alice", own.id))
    }

    @Test
    fun `a row the policy rejects is denied`() = runBlocking {
        val theirs = article("a-theirs", createdBy = "bob")
        coEvery { articleStore.findById(theirs.id) } returns theirs
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertFalse(guard.canReadEntry(context, "bucket", theirs.id))
    }

    @Test
    fun `an indexed draft is still denied`() = runBlocking {
        val draft = article("a-draft", createdBy = "alice", status = ArticleStatus.DRAFT, ragEnabled = false)
        coEvery { articleStore.findById(draft.id) } returns draft
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertFalse(guard.canReadEntry(context, "bucket", draft.id))
    }

    @Test
    fun `a stale index entry for a retrieval-disabled article is denied`() = runBlocking {
        val hidden = article("a-hidden", createdBy = "alice", ragEnabled = false)
        coEvery { articleStore.findById(hidden.id) } returns hidden
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertFalse(guard.canReadEntry(context, "bucket", hidden.id))
    }

    @Test
    fun `batch filtering drops a stale entry for a retrieval-disabled article`() = runBlocking {
        val visible = article("a-visible", createdBy = "alice")
        val hidden = article("a-batch-hidden", createdBy = "alice", ragEnabled = false)
        coEvery { articleStore.findByIds("bucket", listOf(visible.id, hidden.id)) } returns
            listOf(visible, hidden)
        val guard = KnowledgeAccessGuard(DefaultKnowledgeAccessPolicy(), articleStore)

        assertEquals(
            setOf(visible.id),
            guard.filterReadableEntries(context, "bucket", listOf(visible.id, hidden.id)),
        )
    }
}
