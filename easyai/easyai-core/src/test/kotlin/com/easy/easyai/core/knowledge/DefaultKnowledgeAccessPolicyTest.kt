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
        folderId = "folder-1",
        title = "A",
        description = null,
        category = "other",
        ext = "md",
        origin = "manual",
        createdAt = 1L,
        updatedAt = 1L,
    )

    private fun context(userId: String, groupUserId: String? = null) =
        KnowledgeAccessContext(userId = userId, groupUserId = groupUserId)

    @Test
    fun `personal rows follow the owner bucket`() = runBlocking {
        val policy = DefaultKnowledgeAccessPolicy()

        assertTrue(policy.canRead(context("alice"), article(userId = "alice")))
        assertFalse(policy.canRead(context("bob"), article(userId = "alice")))
    }

    @Test
    fun `group rows are shared only when sharing is on`() = runBlocking {
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
    fun `folder management defaults to the create right`() = runBlocking {
        val policy = DefaultKnowledgeAccessPolicy()

        assertTrue(policy.canManageFolders(context("alice")))
    }

    @Test
    fun `a host can narrow folder management independently of creation`() = runBlocking {
        val readOnlyCreator = object : KnowledgeAccessPolicy {
            override suspend fun canView(context: KnowledgeAccessContext) = true
            override suspend fun canCreate(context: KnowledgeAccessContext) = true
            override suspend fun canManageFolders(context: KnowledgeAccessContext) = false
            override suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
            override suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
        }

        assertTrue(readOnlyCreator.canCreate(context("alice")))
        assertFalse(readOnlyCreator.canManageFolders(context("alice")))
    }
}
