package com.easy.easyai.core.knowledge

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DefaultKnowledgeAccessPolicyTest {

    private fun article(
        userId: String = "alice",
        createdBy: String? = null,
    ) = KnowledgeArticle(
        id = "a1",
        userId = userId,
        createdBy = createdBy,
        knowledgeKey = "manual/a.md",
        ossKey = "oss/a.md",
        title = "A",
        description = null,
        category = "other",
        source = "manual",
        relativePath = "a.md",
        ext = "md",
        contentHash = null,
        origin = "manual",
        originSessionId = null,
        originMessageId = null,
        composedFrom = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun context(userId: String, groupUserId: String? = null) =
        KnowledgeAccessContext(userId = userId, groupUserId = groupUserId)

    @Test
    fun `legacy personal rows follow the owner bucket`() = runBlocking {
        val policy = DefaultKnowledgeAccessPolicy()

        assertTrue(policy.canRead(context("alice"), article(userId = "alice")))
        assertFalse(policy.canRead(context("bob"), article(userId = "alice")))
    }

    @Test
    fun `legacy group rows are shared only when sharing is on`() = runBlocking {
        val row = article(userId = "grp-1")

        assertFalse(DefaultKnowledgeAccessPolicy().canRead(context("alice", "grp-1"), row))
        assertTrue(DefaultKnowledgeAccessPolicy(sharedWithinGroup = true).canRead(context("alice", "grp-1"), row))
    }

    @Test
    fun `a creator stamp narrows access to that creator`() = runBlocking {
        val policy = DefaultKnowledgeAccessPolicy(sharedWithinGroup = true)
        val row = article(userId = "grp-1", createdBy = "alice")

        assertTrue(policy.canRead(context("alice", "grp-1"), row))
        assertFalse(policy.canRead(context("bob", "grp-1"), row))
    }

    @Test
    fun `entries without metadata stay readable under the default policy`() = runBlocking {
        assertTrue(
            DefaultKnowledgeAccessPolicy().canReadEntry(context("alice"), "grp-1", "manual/a.md", null)
        )
    }
}
