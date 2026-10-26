package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.core.knowledge.DefaultKnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeUploadItem
import com.easy.easyai.core.knowledge.UploadResult
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.sql.SQLException
import java.util.Base64
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeArticleServiceTest {

    private val articleStore = mockk<KnowledgeArticleStore>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>(relaxed = true)

    private fun service(
        resolver: ObjectStorageResolver? = null,
        knowledgeStore: KnowledgeStore? = null,
        sharedWithinGroup: Boolean = false,
        policy: KnowledgeAccessPolicy = DefaultKnowledgeAccessPolicy(sharedWithinGroup),
    ) = KnowledgeArticleService(
        objectStorageResolver = resolver,
        knowledgeStore = knowledgeStore,
        articleStore = articleStore,
        accessPolicy = policy,
        configStore = configStore,
        modelFactories = emptyList(),
        sharedWithinGroup = sharedWithinGroup
    )

    private fun context(userId: String = "alice", groupUserId: String? = null) =
        KnowledgeAccessContext(userId = userId, groupUserId = groupUserId)

    private fun article(
        id: String = "article-1",
        userId: String = "alice",
        createdBy: String? = "alice",
        knowledgeKey: String = "manual/a.md",
        ossKey: String = "knowledge-articles/encoded/manual/a.md",
        title: String = "A",
        createdAt: Long = 1L
    ) = KnowledgeArticle(
        id = id,
        userId = userId,
        createdBy = createdBy,
        knowledgeKey = knowledgeKey,
        ossKey = ossKey,
        title = title,
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
        createdAt = createdAt,
        updatedAt = createdAt
    )

    @Nested
    inner class PolicyAccess {

        @Test
        fun `creator can read and write their own article`() = runTest {
            val owned = article()
            val svc = service()

            assertTrue(svc.canWrite(context(), owned))
            assertTrue(svc.canManage(context(), owned))
        }

        @Test
        fun `another user cannot read a creator-stamped article`() = runTest {
            val owned = article()
            val svc = service()

            assertFalse(svc.canWrite(context("bob"), owned))
        }

        @Test
        fun `legacy row without createdBy falls back to the owner bucket`() = runTest {
            val legacy = article(userId = "bob", createdBy = null)
            val svc = service()

            assertTrue(svc.canWrite(context("bob"), legacy))
            assertFalse(svc.canWrite(context("alice"), legacy))
        }

        @Test
        fun `legacy group row stays writable by any member in a shared group`() = runTest {
            val legacy = article(userId = "grp-1", createdBy = null)
            val svc = service(sharedWithinGroup = true)

            assertTrue(svc.canWrite(context("bob", "grp-1"), legacy))
        }
    }

    @Nested
    inner class ListAccessible {

        @Test
        fun `unreadable rows are filtered out`() = runTest {
            val mine = article(id = "mine", knowledgeKey = "manual/mine.md")
            val theirs = article(id = "theirs", createdBy = "bob", knowledgeKey = "manual/theirs.md")
            coEvery {
                articleStore.findAllInBuckets(listOf("alice"), null, null)
            } returns flowOf(mine, theirs)
            val svc = service()

            val visible = svc.listAccessible(context())

            assertEquals(listOf("mine"), visible.map { it.article.id })
        }

        @Test
        fun `personal bucket row shadows a same-key group row`() = runTest {
            val personal = article(id = "personal", knowledgeKey = "manual/a.md")
            val group = article(id = "group", userId = "grp-1", knowledgeKey = "manual/a.md")
            coEvery { articleStore.findAllInBuckets(listOf("alice"), null, null) } returns flowOf(personal)
            coEvery { articleStore.findAllInBuckets(listOf("grp-1"), null, null) } returns flowOf(group)
            val svc = service(sharedWithinGroup = true)

            val visible = svc.listAccessible(context(groupUserId = "grp-1"))

            assertEquals(listOf("personal"), visible.map { it.article.id })
        }
    }

    @Nested
    inner class ContentAccess {

        @Test
        fun `readContent returns content for the creator`() = runTest {
            val owned = article()
            val bytes = "hello".toByteArray()
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", owned.knowledgeKey) } returns owned
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.get(owned.ossKey) } returns
                ObjectContent(ObjectMeta(owned.ossKey, bytes.size.toLong()), bytes)
            val svc = service(resolver = resolver)

            assertEquals("hello", svc.readContent(context(), owned.knowledgeKey))
        }

        @Test
        fun `readContent hides articles the policy rejects`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findByKey("alice", theirs.knowledgeKey) } returns theirs
            val svc = service()

            assertNull(svc.readContent(context(), theirs.knowledgeKey))
        }

        @Test
        fun `updateArticle rejects a reader without write access`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findByKey("alice", theirs.knowledgeKey) } returns theirs
            val svc = service()

            val error = assertFailsWith<ResponseStatusException> {
                svc.updateArticle(context(), theirs.knowledgeKey, "new content")
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        }

        @Test
        fun `deleteArticle rejects a caller without manage access`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findByKey("alice", theirs.knowledgeKey) } returns theirs
            val svc = service()

            val error = assertFailsWith<ResponseStatusException> {
                svc.deleteArticle(context(), theirs.knowledgeKey)
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        }

        @Test
        fun `aiRewrite rejects a reader without write access`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findByKey("alice", theirs.knowledgeKey) } returns theirs
            val svc = service()

            val error = assertFailsWith<ResponseStatusException> {
                svc.aiRewrite(
                    context = context(),
                    key = theirs.knowledgeKey,
                    content = "body",
                    instruction = "shorten",
                    modelConfigId = "model-1",
                    owners = setOf("alice"),
                )
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
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
                    context = context(),
                    title = "A",
                    content = "body",
                    source = "manual",
                    relativePath = "a.md",
                    category = "other",
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
                context = context(),
                title = "New",
                content = "fresh",
                source = "manual",
                relativePath = "a.md",
                category = "other",
                origin = "upload"
            )

            assertEquals(existing.id, saved.id)
            assertEquals(42L, saved.createdAt)
            assertEquals("New", saved.title)
        }

        @Test
        fun `new article is stamped with its creator`() = runTest {
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", "manual/a.md") } returns null
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta("", 0)
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver).saveArticle(
                context = context(),
                title = "A",
                content = "body",
                source = "manual",
                relativePath = "a.md",
                category = "other",
                origin = "chat_save"
            )

            coVerify { articleStore.save(capture(saved)) }
            assertEquals("alice", saved.captured.userId)
            assertEquals("alice", saved.captured.createdBy)
        }

        @Test
        fun `shared group writes into the group bucket`() = runTest {
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("grp-1", "manual/a.md") } returns null
            coEvery { resolver.resolve("grp-1") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta("", 0)
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver, sharedWithinGroup = true).saveArticle(
                context = context(groupUserId = "grp-1"),
                title = "A",
                content = "body",
                source = "manual",
                relativePath = "a.md",
                category = "other",
                origin = "chat_save"
            )

            coVerify { articleStore.save(capture(saved)) }
            assertEquals("grp-1", saved.captured.userId)
            assertEquals("alice", saved.captured.createdBy)
        }

        @Test
        fun `personal mode ignores the group bucket`() = runTest {
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", "manual/a.md") } returns null
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta("", 0)
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver).saveArticle(
                context = context(groupUserId = "grp-1"),
                title = "A",
                content = "body",
                source = "manual",
                relativePath = "a.md",
                category = "other",
                origin = "chat_save"
            )

            coVerify { articleStore.save(capture(saved)) }
            assertEquals("alice", saved.captured.userId)
        }
    }

    @Nested
    inner class UpdateArticle {

        @Test
        fun `blank oss key is rebuilt and content is written before metadata`() = runTest {
            val legacy = article(ossKey = "")
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", legacy.knowledgeKey) } returns legacy
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta("", 0)
            val expectedKey = "knowledge-articles/" +
                Base64.getUrlEncoder().withoutPadding().encodeToString("alice".toByteArray()) +
                "/manual/a.md"

            service(resolver = resolver).updateArticle(context(), legacy.knowledgeKey, "new content")

            coVerifyOrder {
                storage.put(expectedKey, any(), "text/markdown")
                articleStore.save(match { it.ossKey == expectedKey })
            }
        }

        @Test
        fun `storage failure leaves metadata untouched`() = runTest {
            val existing = article()
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", existing.knowledgeKey) } returns existing
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } throws IllegalStateException("oss down")

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver).updateArticle(context(), existing.knowledgeKey, "new content")
            }

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.statusCode)
            coVerify(exactly = 0) { articleStore.save(any()) }
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `traversal relative path is rejected`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                service().saveArticle(
                    context = context(),
                    title = "A",
                    content = "body",
                    source = "manual",
                    relativePath = "../a.md",
                    category = "other",
                    origin = "manual"
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `source with a separator is rejected`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                service().saveArticle(
                    context = context(),
                    title = "A",
                    content = "body",
                    source = "a/b",
                    relativePath = "a.md",
                    category = "other",
                    origin = "manual"
                )
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `malformed update key is rejected before lookup`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                service().updateArticle(context(), "manual/../../secret.md", "body")
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            coVerify(exactly = 0) { articleStore.findByKey(any(), any()) }
        }
    }

    @Nested
    inner class UniqueViolation {

        @Test
        fun `a racy duplicate maps to 409`() = runTest {
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKey("alice", "manual/a.md") } returns null
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), "text/markdown") } returns ObjectMeta("", 0)
            coEvery { articleStore.save(any()) } throws
                SQLException("duplicate key value violates unique constraint", "23505")

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver).saveArticle(
                    context = context(),
                    title = "A",
                    content = "body",
                    source = "manual",
                    relativePath = "a.md",
                    category = "other",
                    origin = "manual"
                )
            }

            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }
    }

    @Nested
    inner class UploadItems {

        @Test
        fun `denied items never reach the RAG store`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findByKeys("alice", listOf("manual/a.md")) } returns emptyList()
            val denyCreate = object : KnowledgeAccessPolicy {
                override suspend fun canCreate(context: KnowledgeAccessContext) = false
                override suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
                override suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
            }

            val results = service(knowledgeStore = store, policy = denyCreate).uploadItems(
                context = context(),
                source = "manual",
                category = null,
                items = listOf(KnowledgeUploadItem("a.md", "body")),
            )

            assertFalse(results.single().success)
            assertEquals("无权限覆盖或创建该文档", results.single().reason)
            coVerify(exactly = 0) { store.uploadBatch(any(), any(), any(), any()) }
        }

        @Test
        fun `metadata failure rolls back a newly indexed entry`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { articleStore.findByKeys("alice", listOf("manual/a.md")) } returns emptyList()
            coEvery { articleStore.findByKey("alice", "manual/a.md") } returns null
            coEvery { articleStore.save(any()) } throws IllegalStateException("db down")
            coEvery { resolver.resolve("alice") } returns mockk<ObjectStorage>(relaxed = true)
            coEvery { store.uploadBatch(any(), any(), any(), any()) } returns
                listOf(UploadResult("a.md", true, "manual/a.md"))
            coEvery { store.delete(any(), any()) } returns true

            val results = service(resolver = resolver, knowledgeStore = store).uploadItems(
                context = context(),
                source = "manual",
                category = null,
                items = listOf(KnowledgeUploadItem("a.md", "body")),
            )

            assertFalse(results.single().success)
            assertEquals("元数据保存失败", results.single().reason)
            coVerify { articleStore.save(any()) }
            coVerify { store.delete("alice", "manual/a.md") }
        }
    }
}
