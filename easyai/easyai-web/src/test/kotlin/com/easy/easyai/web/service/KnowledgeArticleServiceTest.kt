package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.DefaultKnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeFolder
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.storage.ObjectContent
import com.easy.easyai.core.storage.ObjectMeta
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.coVerifyOrder
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeArticleServiceTest {

    private val articleStore = mockk<KnowledgeArticleStore>(relaxed = true)
    private val configStore = mockk<ModelProviderConfigStore>(relaxed = true)
    private val folderService = mockk<KnowledgeFolderService>(relaxed = true)

    private fun service(
        resolver: ObjectStorageResolver? = null,
        knowledgeStore: KnowledgeStore? = null,
        sharedWithinGroup: Boolean = false,
        policy: KnowledgeAccessPolicy = DefaultKnowledgeAccessPolicy(sharedWithinGroup),
    ) = KnowledgeArticleService(
        objectStorageResolver = resolver,
        knowledgeStore = knowledgeStore,
        articleStore = articleStore,
        folderService = folderService,
        accessPolicy = policy,
        configStore = configStore,
        modelFactories = emptyList(),
        sharedWithinGroup = sharedWithinGroup,
    )

    private fun context(userId: String = "alice", groupUserId: String? = null) =
        KnowledgeAccessContext(userId = userId, groupUserId = groupUserId)

    private fun article(
        id: String = "article-1",
        userId: String = "alice",
        createdBy: String? = "alice",
        folderId: String = "folder-1",
        fileName: String? = null,
        title: String = "A",
        category: String = "other",
        ext: String = "md",
        origin: String = "manual",
        status: ArticleStatus = ArticleStatus.PUBLISHED,
        ragEnabled: Boolean = false,
        createdAt: Long = 1L,
    ) = KnowledgeArticle(
        id = id,
        userId = userId,
        createdBy = createdBy,
        folderId = folderId,
        fileName = fileName,
        title = title,
        description = null,
        category = category,
        ext = ext,
        origin = origin,
        status = status,
        ragEnabled = ragEnabled,
        createdAt = createdAt,
        updatedAt = createdAt,
    )

    private fun folder(
        id: String = "folder-1",
        userId: String = "alice",
        parentId: String? = null,
        name: String = "Root",
        isDefault: Boolean = true,
    ) = KnowledgeFolder(
        id = id,
        userId = userId,
        parentId = parentId,
        name = name,
        isDefault = isDefault,
        createdBy = null,
        createdAt = 1L,
        updatedAt = 1L,
    )

    /** A resolver whose storage accepts writes; stub [ObjectStorage.get] per test that reads. */
    private fun mockStorage(): Pair<ObjectStorageResolver, ObjectStorage> {
        val storage = mockk<ObjectStorage>()
        val resolver = mockk<ObjectStorageResolver>()
        coEvery { resolver.resolve(any<String>()) } returns storage
        coEvery { storage.put(any(), any(), any()) } returns ObjectMeta("", 0)
        coEvery { storage.delete(any()) } returns true
        return resolver to storage
    }

    private fun storageKey(id: String) = "knowledge-articles/$id"

    private fun stubContent(storage: ObjectStorage, id: String, body: String) {
        coEvery { storage.get(storageKey(id)) } returns
            ObjectContent(ObjectMeta(storageKey(id), body.length.toLong()), body.toByteArray())
    }

    @Nested
    inner class PolicyAccess {

        @Test
        fun `creator can read and write their own article`() = runTest {
            val svc = service()

            assertTrue(svc.canWrite(context(), article()))
            assertTrue(svc.canManage(context(), article()))
        }

        @Test
        fun `another user cannot write a creator-stamped article`() = runTest {
            val svc = service()

            assertFalse(svc.canWrite(context("bob"), article()))
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
    inner class SaveArticle {

        @Test
        fun `a draft cannot be indexed`() = runTest {
            val (resolver, _) = mockStorage()
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver).saveArticle(
                    context = context(),
                    title = "A",
                    content = "body",
                    folderId = "folder-1",
                    category = "other",
                    origin = "manual",
                    status = ArticleStatus.DRAFT,
                    ragEnabled = true,
                )
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `saving directly into a retrievable state is rejected`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                service().saveArticle(
                    context = context(),
                    title = "A",
                    content = "body",
                    folderId = "folder-1",
                    category = "other",
                    origin = "manual",
                    status = ArticleStatus.PUBLISHED,
                    ragEnabled = true,
                )
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `create is rejected without permission`() = runTest {
            val denyCreate = object : KnowledgeAccessPolicy {
                override suspend fun canCreate(context: KnowledgeAccessContext) = false
                override suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
                override suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
            }

            val error = assertFailsWith<ResponseStatusException> {
                service(policy = denyCreate).saveArticle(
                    context = context(),
                    title = "A",
                    content = "body",
                    folderId = "folder-1",
                    category = "other",
                    origin = "manual",
                )
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        }

        @Test
        fun `new article is stamped with its creator and written to storage`() = runTest {
            val (resolver, storage) = mockStorage()
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver).saveArticle(
                context = context(),
                title = "A",
                content = "body",
                folderId = "folder-1",
                category = "other",
                origin = "chat_save",
            )

            coVerify { articleStore.save(capture(saved)) }
            assertEquals("alice", saved.captured.userId)
            assertEquals("alice", saved.captured.createdBy)
            assertEquals(ArticleStatus.DRAFT, saved.captured.status)
            assertFalse(saved.captured.ragEnabled)
            coVerify { storage.put("${storageKey(saved.captured.id)}", any(), "text/markdown") }
        }

        @Test
        fun `shared group writes into the group bucket`() = runTest {
            val (resolver, _) = mockStorage()
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder(id = "folder-1", userId = "grp-1")
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver, sharedWithinGroup = true).saveArticle(
                context = context(groupUserId = "grp-1"),
                title = "A",
                content = "body",
                folderId = "folder-1",
                category = "other",
                origin = "chat_save",
            )

            coVerify { articleStore.save(capture(saved)) }
            assertEquals("grp-1", saved.captured.userId)
            assertEquals("alice", saved.captured.createdBy)
        }

        @Test
        fun `a racy duplicate file name maps to 409`() = runTest {
            val (resolver, _) = mockStorage()
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()
            coEvery { articleStore.save(any()) } throws
                SQLException("duplicate key value violates unique constraint", "23505")

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver).saveArticle(
                    context = context(),
                    title = "A",
                    content = "body",
                    folderId = "folder-1",
                    category = "other",
                    origin = "manual",
                    fileName = "a.md",
                )
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }
    }

    @Nested
    inner class UpdateArticle {

        @Test
        fun `rejects a reader without write access`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findById(theirs.id) } returns theirs

            val error = assertFailsWith<ResponseStatusException> {
                service().updateArticle(context(), theirs.id, "new content")
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        }

        @Test
        fun `writes content to storage before metadata and skips the index for a draft`() = runTest {
            val existing = article(status = ArticleStatus.DRAFT, ragEnabled = false)
            val (resolver, storage) = mockStorage()
            coEvery { articleStore.findById(existing.id) } returns existing

            val result = service(resolver = resolver).updateArticle(context(), existing.id, "new content")

            assertFalse(result.indexPending)
            coVerifyOrder {
                storage.put(storageKey(existing.id), any(), "text/markdown")
                articleStore.save(match { it.description == "new content" })
            }
        }

        @Test
        fun `re-indexes a published rag article and reports a failed index write`() = runTest {
            val existing = article(status = ArticleStatus.PUBLISHED, ragEnabled = true)
            val (resolver, storage) = mockStorage()
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(existing.id) } returns existing
            coEvery { store.index(any(), any(), any(), any(), any(), any()) } returns false

            val result = service(resolver = resolver, knowledgeStore = store)
                .updateArticle(context(), existing.id, "new content")

            assertTrue(result.indexPending)
            coVerify { storage.put(storageKey(existing.id), any(), "text/markdown") }
            coVerify { articleStore.save(any()) }
        }

        @Test
        fun `storage failure leaves metadata untouched`() = runTest {
            val existing = article()
            val storage = mockk<ObjectStorage>()
            val resolver = mockk<ObjectStorageResolver>()
            coEvery { resolver.resolve("alice") } returns storage
            coEvery { storage.put(any(), any(), any()) } throws IllegalStateException("oss down")
            coEvery { articleStore.findById(existing.id) } returns existing

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver).updateArticle(context(), existing.id, "new content")
            }

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.statusCode)
            coVerify(exactly = 0) { articleStore.save(any()) }
        }
    }

    @Nested
    inner class MoveArticle {

        @Test
        fun `moving is a database-only folder change`() = runTest {
            val existing = article(folderId = "folder-1", ragEnabled = true)
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(existing.id) } returns existing
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder(id = "folder-2")
            coEvery { folderService.foldersIn(any()) } returns listOf(folder(id = "folder-2", name = "Target"))
            val moved = slot<KnowledgeArticle>()

            service(knowledgeStore = store).moveArticle(context(), existing.id, "folder-2")

            coVerify { articleStore.save(capture(moved)) }
            assertEquals("folder-2", moved.captured.folderId)
            coVerify(exactly = 0) { store.index(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { store.delete(any(), any()) }
        }

        @Test
        fun `rejects a caller without write access`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findById(theirs.id) } returns theirs

            val error = assertFailsWith<ResponseStatusException> {
                service().moveArticle(context(), theirs.id, "folder-2")
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        }

        @Test
        fun `a file name already taken in the target folder conflicts`() = runTest {
            val existing = article(folderId = "folder-1", fileName = "notes.md")
            coEvery { articleStore.findById(existing.id) } returns existing
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder(id = "folder-2")
            coEvery { articleStore.save(any()) } throws
                SQLException("duplicate key value violates unique constraint \"uq_ka_folder_file\"", "23505")

            val error = assertFailsWith<ResponseStatusException> {
                service().moveArticle(context(), existing.id, "folder-2")
            }

            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }
    }

    @Nested
    inner class ContentAccess {

        @Test
        fun `readContent returns content for the creator`() = runTest {
            val owned = article()
            val (resolver, storage) = mockStorage()
            stubContent(storage, owned.id, "hello")
            coEvery { articleStore.findById(owned.id) } returns owned

            assertEquals("hello", service(resolver = resolver).readContent(context(), owned.id))
        }

        @Test
        fun `readContent hides articles the policy rejects`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findById(theirs.id) } returns theirs

            assertNull(service().readContent(context(), theirs.id))
        }

        @Test
        fun `another user's draft answers 404 like a missing id`() = runTest {
            val draft = article(createdBy = "bob", status = ArticleStatus.DRAFT)
            coEvery { articleStore.findById(draft.id) } returns draft

            val error = assertFailsWith<ResponseStatusException> {
                service().readContent(context(), draft.id)
            }
            assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        }
    }

    @Nested
    inner class DeleteArticle {

        @Test
        fun `rejects a caller without manage access`() = runTest {
            val theirs = article(createdBy = "bob")
            coEvery { articleStore.findById(theirs.id) } returns theirs

            val error = assertFailsWith<ResponseStatusException> {
                service().deleteArticle(context(), theirs.id)
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        }

        @Test
        fun `removes the index entry before storage and metadata`() = runTest {
            val existing = article(ragEnabled = true)
            val (resolver, storage) = mockStorage()
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(existing.id) } returns existing
            coEvery { store.delete(existing.userId, existing.id) } returns true

            assertTrue(service(resolver = resolver, knowledgeStore = store).deleteArticle(context(), existing.id))

            coVerifyOrder {
                store.delete(existing.userId, existing.id)
                storage.delete(storageKey(existing.id))
                articleStore.delete(existing.id)
            }
        }

        @Test
        fun `deletion is allowed while retrieval is disabled for the deployment`() = runTest {
            val existing = article(ragEnabled = true)
            val (resolver, storage) = mockStorage()
            coEvery { articleStore.findById(existing.id) } returns existing

            assertTrue(service(resolver = resolver, knowledgeStore = null).deleteArticle(context(), existing.id))

            coVerify { storage.delete(storageKey(existing.id)) }
            coVerify { articleStore.delete(existing.id) }
        }

        @Test
        fun `a failed index removal keeps the article published`() = runTest {
            val existing = article(ragEnabled = true)
            val (resolver, _) = mockStorage()
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(existing.id) } returns existing
            coEvery { store.delete(existing.userId, existing.id) } returns false
            coEvery { store.read(existing.userId, existing.id) } returns "still here"

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver, knowledgeStore = store).deleteArticle(context(), existing.id)
            }

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.statusCode)
            coVerify(exactly = 0) { articleStore.delete(any()) }
        }
    }

    @Nested
    inner class Lifecycle {

        @Test
        fun `publish flips a draft to published and indexes it`() = runTest {
            val draft = article(status = ArticleStatus.DRAFT, ragEnabled = false)
            val (resolver, storage) = mockStorage()
            stubContent(storage, draft.id, "body")
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(draft.id) } returns draft
            coEvery { store.index(any(), any(), any(), any(), any(), any()) } returns true
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver, knowledgeStore = store).publish(context(), draft.id)

            coVerify { articleStore.save(capture(saved)) }
            assertEquals(ArticleStatus.PUBLISHED, saved.captured.status)
            assertTrue(saved.captured.ragEnabled)
            coVerify { store.index(draft.userId, draft.id, "body", draft.title, draft.category, draft.ext) }
        }

        @Test
        fun `a failed first publish reverts to draft and drops the partial entry`() = runTest {
            val draft = article(status = ArticleStatus.DRAFT, ragEnabled = false)
            val (resolver, storage) = mockStorage()
            stubContent(storage, draft.id, "body")
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(draft.id) } returns draft
            coEvery { store.index(any(), any(), any(), any(), any(), any()) } returns false

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver, knowledgeStore = store).publish(context(), draft.id)
            }

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.statusCode)
            coVerify { store.delete(draft.userId, draft.id) }
            coVerify { articleStore.save(match { it.status == ArticleStatus.DRAFT && !it.ragEnabled }) }
        }

        @Test
        fun `unpublish removes the index entry and demotes to draft`() = runTest {
            val published = article(status = ArticleStatus.PUBLISHED, ragEnabled = true)
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(published.id) } returns published
            coEvery { store.delete(published.userId, published.id) } returns true
            val saved = slot<KnowledgeArticle>()

            service(knowledgeStore = store).unpublish(context(), published.id)

            coVerify { articleStore.save(capture(saved)) }
            assertEquals(ArticleStatus.DRAFT, saved.captured.status)
            assertFalse(saved.captured.ragEnabled)
        }

        @Test
        fun `enabling retrieval on a draft is rejected`() = runTest {
            val draft = article(status = ArticleStatus.DRAFT, ragEnabled = false)
            coEvery { articleStore.findById(draft.id) } returns draft

            val error = assertFailsWith<ResponseStatusException> {
                service(knowledgeStore = mockk(relaxed = true)).setRag(context(), draft.id, true)
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `enabling retrieval on a published article indexes it`() = runTest {
            val published = article(status = ArticleStatus.PUBLISHED, ragEnabled = false)
            val (resolver, storage) = mockStorage()
            stubContent(storage, published.id, "body")
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(published.id) } returns published
            coEvery { store.index(any(), any(), any(), any(), any(), any()) } returns true
            val saved = slot<KnowledgeArticle>()

            service(resolver = resolver, knowledgeStore = store).setRag(context(), published.id, true)

            coVerify { articleStore.save(capture(saved)) }
            assertTrue(saved.captured.ragEnabled)
        }

        @Test
        fun `a failed index write rolls the flag back to hidden`() = runTest {
            val published = article(status = ArticleStatus.PUBLISHED, ragEnabled = false)
            val (resolver, storage) = mockStorage()
            stubContent(storage, published.id, "body")
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(published.id) } returns published
            coEvery { store.index(any(), any(), any(), any(), any(), any()) } returns false
            coEvery { store.read(any(), any()) } returns null

            val error = assertFailsWith<ResponseStatusException> {
                service(resolver = resolver, knowledgeStore = store).setRag(context(), published.id, true)
            }

            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.statusCode)
            // The optimistic flag flip is reverted, so the row never claims to be retrievable
            // without an index entry.
            coVerify { articleStore.save(match { it.ragEnabled }) }
            coVerify { articleStore.save(match { !it.ragEnabled }) }
            coVerify { store.delete(published.userId, published.id) }
        }

        @Test
        fun `disabling retrieval removes the index entry`() = runTest {
            val published = article(status = ArticleStatus.PUBLISHED, ragEnabled = true)
            val store = mockk<KnowledgeStore>(relaxed = true)
            coEvery { articleStore.findById(published.id) } returns published
            coEvery { store.delete(published.userId, published.id) } returns true
            val saved = slot<KnowledgeArticle>()

            service(knowledgeStore = store).setRag(context(), published.id, false)

            coVerify { articleStore.save(capture(saved)) }
            assertFalse(saved.captured.ragEnabled)
        }
    }

    @Nested
    inner class ListAccessible {

        @Test
        fun `unreadable rows are filtered out`() = runTest {
            val mine = article(id = "mine")
            val theirs = article(id = "theirs", createdBy = "bob")
            coEvery { articleStore.findAllInBuckets(listOf("alice"), null, null, null) } returns flowOf(mine, theirs)

            val visible = service().listAccessible(context())

            assertEquals(listOf("mine"), visible.map { it.article.id })
        }

        @Test
        fun `another creator's draft is hidden`() = runTest {
            val mine = article(id = "mine")
            val theirDraft = article(id = "draft", createdBy = "bob", status = ArticleStatus.DRAFT)
            coEvery { articleStore.findAllInBuckets(listOf("alice"), null, null, null) } returns
                flowOf(mine, theirDraft)

            val visible = service().listAccessible(context())

            assertEquals(listOf("mine"), visible.map { it.article.id })
        }

        @Test
        fun `the same id is listed once even across buckets`() = runTest {
            val personal = article(id = "dup", userId = "alice")
            coEvery { articleStore.findAllInBuckets(listOf("alice"), null, null, null) } returns flowOf(personal)
            coEvery { articleStore.findAllInBuckets(listOf("grp-1"), null, null, null) } returns flowOf(personal)

            val visible = service(sharedWithinGroup = true).listAccessible(context(groupUserId = "grp-1"))

            assertEquals(listOf("dup"), visible.map { it.article.id })
        }
    }

    @Nested
    inner class UploadItems {

        @Test
        fun `denied items never reach the store`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)
            every { store.supports(any()) } returns true
            val existing = article(fileName = "a.md", createdBy = "bob", status = ArticleStatus.PUBLISHED)
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()
            coEvery { articleStore.findByFolderAndFileName("alice", "folder-1", "a.md") } returns existing
            val (resolver, _) = mockStorage()
            val denyWrite = object : KnowledgeAccessPolicy {
                override suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
                override suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle) = false
            }

            val results = service(resolver = resolver, knowledgeStore = store, policy = denyWrite).uploadItems(
                context = context(),
                folderId = "folder-1",
                category = null,
                items = listOf(KnowledgeUploadItem("a.md", "body")),
            )

            assertFalse(results.single().success)
            assertEquals("无权限覆盖或创建该文档", results.single().reason)
            coVerify(exactly = 0) { store.index(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `upload never overwrites a draft`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)
            every { store.supports(any()) } returns true
            val draft = article(fileName = "a.md", status = ArticleStatus.DRAFT)
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()
            coEvery { articleStore.findByFolderAndFileName("alice", "folder-1", "a.md") } returns draft
            val (resolver, _) = mockStorage()

            val results = service(resolver = resolver, knowledgeStore = store).uploadItems(
                context = context(),
                folderId = "folder-1",
                category = null,
                items = listOf(KnowledgeUploadItem("a.md", "body")),
            )

            assertFalse(results.single().success)
            assertEquals("草稿不支持上传覆盖，请先发布或删除", results.single().reason)
            coVerify(exactly = 0) { store.index(any(), any(), any(), any(), any(), any()) }
        }

        @Test
        fun `an unsupported extension is rejected`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)
            every { store.supports(any()) } returns false
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()
            coEvery { articleStore.findByFolderAndFileName(any(), any(), any()) } returns null
            val (resolver, _) = mockStorage()

            val results = service(resolver = resolver, knowledgeStore = store).uploadItems(
                context = context(),
                folderId = "folder-1",
                category = null,
                items = listOf(KnowledgeUploadItem("a.pdf", "body")),
            )

            assertFalse(results.single().success)
            coVerify(exactly = 0) { articleStore.save(any()) }
        }

        @Test
        fun `a new file is published, indexed and written to storage`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)
            every { store.supports(any()) } returns true
            coEvery { store.index(any(), any(), any(), any(), any(), any()) } returns true
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()
            coEvery { articleStore.findByFolderAndFileName(any(), any(), any()) } returns null
            val (resolver, storage) = mockStorage()
            val saved = slot<KnowledgeArticle>()

            val results = service(resolver = resolver, knowledgeStore = store).uploadItems(
                context = context(),
                folderId = "folder-1",
                category = null,
                items = listOf(KnowledgeUploadItem("a.md", "# Title\n\nbody")),
            )

            val outcome = results.single()
            assertTrue(outcome.success)
            assertFalse(outcome.indexPending)
            coVerify { articleStore.save(capture(saved)) }
            assertEquals(ArticleStatus.PUBLISHED, saved.captured.status)
            assertTrue(saved.captured.ragEnabled)
            assertEquals("Title", saved.captured.title)
            coVerify { storage.put(storageKey(saved.captured.id), any(), "text/markdown") }
            coVerify { store.index(any(), saved.captured.id, any(), any(), any(), any()) }
        }

        @Test
        fun `an index failure keeps the article with retrieval disabled`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)
            every { store.supports(any()) } returns true
            coEvery { store.index(any(), any(), any(), any(), any(), any()) } returns false
            coEvery { folderService.requireFolder(any(), any(), any()) } returns folder()
            coEvery { articleStore.findByFolderAndFileName(any(), any(), any()) } returns null
            val (resolver, _) = mockStorage()

            val results = service(resolver = resolver, knowledgeStore = store).uploadItems(
                context = context(),
                folderId = "folder-1",
                category = null,
                items = listOf(KnowledgeUploadItem("a.md", "body")),
            )

            val outcome = results.single()
            assertTrue(outcome.success)
            assertTrue(outcome.indexPending)
            assertEquals("已保存，但检索索引构建失败，请稍后重试", outcome.reason)
            coVerify { articleStore.save(match { !it.ragEnabled }) }
        }
    }

    @Nested
    inner class Validation {

        @Test
        fun `a traversal upload path is rejected`() = runTest {
            val store = mockk<KnowledgeStore>(relaxed = true)

            val error = assertFailsWith<ResponseStatusException> {
                service(knowledgeStore = store).uploadItems(
                    context = context(),
                    folderId = "folder-1",
                    category = null,
                    items = listOf(KnowledgeUploadItem("../a.md", "body")),
                )
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }

        @Test
        fun `a malformed article id is rejected before lookup`() = runTest {
            val error = assertFailsWith<ResponseStatusException> {
                service().readContent(context(), "a/../../secret")
            }

            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            coVerify(exactly = 0) { articleStore.findById(any()) }
        }
    }

    @Nested
    inner class FolderPathResolution {

        @Test
        fun `resolveFolderIdByPath delegates to the folder service in the write bucket`() = runTest {
            coEvery { folderService.resolvePath("alice", "Family/Recipes") } returns folder(id = "folder-9")

            val resolved = service().resolveFolderIdByPath(context(), "Family/Recipes")

            assertEquals("folder-9", resolved)
        }
    }
}
