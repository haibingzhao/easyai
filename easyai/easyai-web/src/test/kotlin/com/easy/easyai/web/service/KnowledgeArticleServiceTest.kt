package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.core.knowledge.KnowledgeAccessStore
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeArticleServiceTest {

    private val articleStore = mockk<KnowledgeArticleStore>(relaxed = true)
    private val accessStore = mockk<KnowledgeAccessStore>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>(relaxed = true)

    private fun service(
        resolver: ObjectStorageResolver? = null,
        knowledgeStore: KnowledgeStore? = null,
        sharedWithinGroup: Boolean = false
    ) = KnowledgeArticleService(
        objectStorageResolver = resolver,
        knowledgeStore = knowledgeStore,
        articleStore = articleStore,
        accessStore = accessStore,
        configStore = configStore,
        modelFactories = emptyList(),
        sharedWithinGroup = sharedWithinGroup
    )

    private fun article(
        id: String = "article-1",
        userId: String = "alice",
        knowledgeKey: String = "manual/a.md",
        ossKey: String = "knowledge-articles/encoded/manual/a.md",
        visibility: String = "private",
        title: String = "A",
        createdAt: Long = 1L
    ) = KnowledgeArticle(
        id = id,
        userId = userId,
        knowledgeKey = knowledgeKey,
        ossKey = ossKey,
        title = title,
        description = null,
        category = "other",
        source = "manual",
        relativePath = "a.md",
        ext = "md",
        contentHash = null,
        visibility = visibility,
        origin = "manual",
        originSessionId = null,
        originMessageId = null,
        composedFrom = null,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Nested
    inner class SharedGroup {

        private val groupId = "group-1"

        @Test
        fun `group bucket rows are writable and manageable by any member`() = runTest {
            val groupArticle = article(userId = groupId)
            val svc = service(sharedWithinGroup = true)

            assertTrue(svc.canWrite(groupArticle, "alice", groupId))
            assertTrue(svc.canManage(groupArticle, "alice", groupId))
        }

        @Test
        fun `member can delete a group bucket article`() = runTest {
            val groupArticle = article(id = "article-g", userId = groupId)
            coEvery { articleStore.findByKey(groupId, groupArticle.knowledgeKey) } returns groupArticle
            val svc = service(sharedWithinGroup = true)

            assertTrue(svc.deleteArticle("alice", groupId, groupArticle.knowledgeKey))
            coVerify(exactly = 1) { articleStore.delete("article-g") }
        }

        @Test
        fun `member can change visibility of a group bucket article`() = runTest {
            val groupArticle = article(id = "article-g", userId = groupId)
            coEvery { articleStore.findById("article-g") } returns groupArticle
            val svc = service(sharedWithinGroup = true)

            svc.updateVisibility("article-g", "group", "alice", groupId)

            coVerify(exactly = 1) {
                articleStore.save(match { it.id == "article-g" && it.visibility == "group" })
            }
        }

        @Test
        fun `listVisible includes private rows from the shared bucket`() = runTest {
            val privateRow = article(userId = groupId, visibility = "private")
            coEvery {
                articleStore.findAllVisible("alice", listOf(groupId), listOf(groupId), null, null)
            } returns flowOf(privateRow)
            val svc = service(sharedWithinGroup = true)

            val visible = svc.listVisible("alice", groupId)

            assertEquals(listOf(privateRow), visible)
        }
    }

    @Nested
    inner class IndividualGrants {

        @Test
        fun `read grant lets a non-owner read content from the owner bucket`() = runTest {
            val owned = article()
            val bytes = "hello".toByteArray()
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("bob", owned.knowledgeKey) } returns null
            coEvery { articleStore.findGrantedByKey("bob", owned.knowledgeKey) } returns owned
            coEvery { accessStore.hasPermission(owned.id, "bob", "read") } returns true
            coEvery { accessStore.hasPermission(owned.id, "bob", "write") } returns false
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.get(owned.ossKey) } returns
                ObjectContent(ObjectMeta(owned.ossKey, bytes.size.toLong()), bytes)
            val svc = service(resolver = resolver)

            assertEquals("hello", svc.readContent("bob", null, owned.knowledgeKey))
        }

        @Test
        fun `write grant saves through the owner bucket, not the caller's`() = runTest {
            val owned = article()
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            val knowledgeStore = mockk<KnowledgeStore>(relaxed = true)
            val putKey = slot<String>()
            coEvery { articleStore.findByKey("bob", owned.knowledgeKey) } returns null
            coEvery { articleStore.findGrantedByKey("bob", owned.knowledgeKey) } returns owned
            coEvery { accessStore.hasPermission(owned.id, "bob", "write") } returns true
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(capture(putKey), any(), "text/markdown") } returns
                ObjectMeta(owned.ossKey, 0)
            coEvery { knowledgeStore.update(any(), any(), any(), any(), any()) } returns true
            val svc = service(resolver = resolver, knowledgeStore = knowledgeStore)

            val updated = svc.updateArticle("bob", null, owned.knowledgeKey, "new content")

            assertEquals(owned.ossKey, putKey.captured)
            assertEquals("alice", updated.userId)
            coVerify(exactly = 1) {
                knowledgeStore.update("alice", owned.knowledgeKey, "new content", null, null)
            }
            coVerify(exactly = 1) {
                articleStore.save(match { it.userId == "alice" && it.knowledgeKey == owned.knowledgeKey })
            }
        }

        @Test
        fun `user without a grant reads nothing`() = runTest {
            val owned = article()
            coEvery { articleStore.findByKey("carol", owned.knowledgeKey) } returns null
            coEvery { articleStore.findGrantedByKey("carol", owned.knowledgeKey) } returns null
            val svc = service()

            assertNull(svc.readContent("carol", null, owned.knowledgeKey))
        }
    }

    @Nested
    inner class SaveArticle {

        @Test
        fun `duplicate title in personal mode returns 409`() = runTest {
            coEvery { articleStore.findByKey("alice", "manual/a.md") } returns article()
            val svc = service()

            val error = assertFailsWith<ResponseStatusException> {
                svc.saveArticle(
                    userId = "alice",
                    groupUserId = null,
                    title = "A",
                    content = "body",
                    source = "manual",
                    relativePath = "a.md",
                    category = "other",
                    visibility = "private",
                    origin = "manual"
                )
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `re-uploading the same path updates the existing row in place`() = runTest {
            val existing = article(title = "Old", createdAt = 42L)
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", existing.knowledgeKey) } returns existing
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta(existing.ossKey, 0)
            val svc = service(resolver = resolver)

            val saved = svc.saveArticle(
                userId = "alice",
                groupUserId = null,
                title = "New",
                content = "fresh",
                source = "manual",
                relativePath = "a.md",
                category = "other",
                visibility = "private",
                origin = "upload"
            )

            assertEquals(existing.id, saved.id)
            assertEquals(42L, saved.createdAt)
            assertEquals("New", saved.title)
        }

        @Test
        fun `group visibility is downgraded to private without a shared group`() = runTest {
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", "manual/a.md") } returns null
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta("", 0)
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver).saveArticle(
                userId = "alice",
                groupUserId = null,
                title = "A",
                content = "body",
                source = "manual",
                relativePath = "a.md",
                category = "other",
                visibility = "group",
                origin = "chat_save"
            )

            coVerify { articleStore.save(capture(saved)) }
            assertEquals("private", saved.captured.visibility)
            assertEquals("alice", saved.captured.userId)
        }

        @Test
        fun `group visibility is preserved inside a shared group bucket`() = runTest {
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("grp-1", "manual/a.md") } returns null
            coEvery { resolver.resolve("grp-1") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta("", 0)
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver, sharedWithinGroup = true).saveArticle(
                userId = "alice",
                groupUserId = "grp-1",
                title = "A",
                content = "body",
                source = "manual",
                relativePath = "a.md",
                category = "other",
                visibility = "group",
                origin = "chat_save"
            )

            coVerify { articleStore.save(capture(saved)) }
            assertEquals("group", saved.captured.visibility)
            assertEquals("grp-1", saved.captured.userId)
        }
    }
}
