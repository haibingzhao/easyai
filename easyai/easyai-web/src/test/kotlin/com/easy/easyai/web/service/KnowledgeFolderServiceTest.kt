package com.easy.easyai.web.service

import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.DefaultKnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessPolicy
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeFolder
import com.easy.easyai.core.knowledge.KnowledgeFolderStore
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KnowledgeFolderServiceTest {

    private val folderStore = mockk<KnowledgeFolderStore>(relaxed = true)
    private val articleStore = mockk<KnowledgeArticleStore>(relaxed = true)

    private fun service(
        sharedWithinGroup: Boolean = false,
        policy: KnowledgeAccessPolicy = DefaultKnowledgeAccessPolicy(sharedWithinGroup),
    ) = KnowledgeFolderService(
        folderStore = folderStore,
        articleStore = articleStore,
        accessPolicy = policy,
        sharedWithinGroup = sharedWithinGroup,
    )

    private fun context(userId: String = "alice", groupUserId: String? = null) =
        KnowledgeAccessContext(userId = userId, groupUserId = groupUserId)

    private fun folder(
        id: String,
        userId: String = "alice",
        parentId: String? = null,
        name: String = "Folder",
        isDefault: Boolean = false,
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

    private fun article(id: String, folderId: String, userId: String = "alice") = KnowledgeArticle(
        id = id,
        userId = userId,
        createdBy = "alice",
        folderId = folderId,
        fileName = null,
        title = "A",
        description = null,
        category = "other",
        ext = "md",
        origin = "manual",
        status = ArticleStatus.PUBLISHED,
        ragEnabled = false,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Nested
    inner class Create {

        @Test
        fun `trims the name and stamps the creator`() = runTest {
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns emptyList()
            val saved = slot<KnowledgeFolder>()

            val created = service().create(context(), parentId = null, name = "  Recipes  ")

            assertEquals("Recipes", created.name)
            assertFalse(created.isDefault)
            assertEquals("alice", created.createdBy)
            coVerify { folderStore.save(capture(saved)) }
            assertNull(saved.captured.parentId)
        }

        @Test
        fun `a duplicate sibling name is rejected`() = runTest {
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(folder("f1", name = "Recipes"))

            val error = assertFailsWith<ResponseStatusException> {
                service().create(context(), parentId = null, name = "Recipes")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `the same name under a different parent is allowed`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1", parentId = null, name = "Family")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns
                listOf(folder("f1", parentId = null, name = "Recipes"))

            val created = service().create(context(), parentId = "f1", name = "Recipes")

            assertEquals("f1", created.parentId)
        }

        @Test
        fun `an unknown parent is 404 and a foreign parent is 400`() = runTest {
            coEvery { folderStore.findById("missing") } returns null
            val notFound = assertFailsWith<ResponseStatusException> {
                service().create(context(), parentId = "missing", name = "X")
            }
            assertEquals(HttpStatus.NOT_FOUND, notFound.statusCode)

            coEvery { folderStore.findById("other") } returns folder("other", userId = "bob")
            val foreign = assertFailsWith<ResponseStatusException> {
                service().create(context(), parentId = "other", name = "X")
            }
            assertEquals(HttpStatus.BAD_REQUEST, foreign.statusCode)
        }

        @Test
        fun `create is forbidden without the manage-folders right`() = runTest {
            val deny = object : KnowledgeAccessPolicy {
                override suspend fun canCreate(context: KnowledgeAccessContext) = false
                override suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
                override suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
            }

            val error = assertFailsWith<ResponseStatusException> {
                service(policy = deny).create(context(), parentId = null, name = "X")
            }
            assertEquals(HttpStatus.FORBIDDEN, error.statusCode)
        }
    }

    @Nested
    inner class Rename {

        @Test
        fun `renaming to the same name is a no-op`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1", name = "Recipes")

            val renamed = service().rename(context(), "f1", "Recipes")

            assertEquals("Recipes", renamed.name)
            coVerify(exactly = 0) { folderStore.save(any()) }
        }

        @Test
        fun `renaming persists the new name`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1", name = "Recipes")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(folder("f1", name = "Recipes"))
            val saved = slot<KnowledgeFolder>()

            service().rename(context(), "f1", "Cooking")

            coVerify { folderStore.save(capture(saved)) }
            assertEquals("Cooking", saved.captured.name)
        }

        @Test
        fun `a duplicate sibling name is rejected`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1", name = "Recipes")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(
                folder("f1", name = "Recipes"),
                folder("f2", name = "Cooking"),
            )

            val error = assertFailsWith<ResponseStatusException> {
                service().rename(context(), "f1", "Cooking")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `a folder outside the caller buckets is 404`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1", userId = "bob")

            val error = assertFailsWith<ResponseStatusException> {
                service().rename(context(), "f1", "X")
            }
            assertEquals(HttpStatus.NOT_FOUND, error.statusCode)
        }
    }

    @Nested
    inner class Delete {

        @Test
        fun `the default folder cannot be deleted`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1", name = "未分类", isDefault = true)

            val error = assertFailsWith<ResponseStatusException> {
                service().delete(context(), "f1")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
            coVerify(exactly = 0) { folderStore.delete(any()) }
        }

        @Test
        fun `a folder with a subfolder is not empty`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(
                folder("f1"),
                folder("child", parentId = "f1"),
            )

            val error = assertFailsWith<ResponseStatusException> {
                service().delete(context(), "f1")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `a folder holding an article in its subtree is not empty`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(
                folder("f1"),
                folder("child", parentId = "f1"),
            )
            coEvery { articleStore.findAllInBuckets(listOf("alice")) } returns
                flowOf(article("a1", folderId = "child"))

            val error = assertFailsWith<ResponseStatusException> {
                service().delete(context(), "f1")
            }
            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }

        @Test
        fun `an empty folder is deleted through the transactional guard`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(folder("f1"))
            coEvery { articleStore.findAllInBuckets(listOf("alice")) } returns flowOf()
            coEvery { folderStore.deleteIfEmpty("f1", setOf("f1")) } returns true

            service().delete(context(), "f1")

            coVerify { folderStore.deleteIfEmpty("f1", setOf("f1")) }
            coVerify(exactly = 0) { folderStore.delete(any()) }
        }

        @Test
        fun `a folder that gained content between the count and the delete conflicts`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(folder("f1"))
            coEvery { articleStore.findAllInBuckets(listOf("alice")) } returns flowOf()
            coEvery { folderStore.deleteIfEmpty("f1", setOf("f1")) } returns false

            val error = assertFailsWith<ResponseStatusException> {
                service().delete(context(), "f1")
            }

            assertEquals(HttpStatus.CONFLICT, error.statusCode)
        }
    }

    @Nested
    inner class RequireFolder {

        @Test
        fun `a blank folder id resolves to the bucket default`() = runTest {
            val default = folder("def", name = "未分类", isDefault = true)
            coEvery { folderStore.findDefault("alice") } returns default

            assertEquals("def", service().requireFolder("alice", null, "alice").id)
            assertEquals("def", service().requireFolder("alice", "  ", "alice").id)
        }

        @Test
        fun `the default folder is created on first use`() = runTest {
            coEvery { folderStore.findDefault("alice") } returns null
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns emptyList()
            val saved = slot<KnowledgeFolder>()

            val resolved = service().requireFolder("alice", null, "alice")

            assertTrue(resolved.isDefault)
            assertEquals("未分类", resolved.name)
            coVerify { folderStore.save(capture(saved)) }
        }

        @Test
        fun `an existing default is adopted rather than recreated`() = runTest {
            coEvery { folderStore.findDefault("alice") } returns null
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns
                listOf(folder("root", name = "未分类", isDefault = false))

            val resolved = service().requireFolder("alice", null, "alice")

            assertEquals("root", resolved.id)
            assertTrue(resolved.isDefault)
        }

        @Test
        fun `a known folder in the bucket is returned`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1")

            assertEquals("f1", service().requireFolder("alice", "f1", "alice").id)
        }

        @Test
        fun `a folder in another bucket is rejected`() = runTest {
            coEvery { folderStore.findById("f1") } returns folder("f1", userId = "bob")

            val error = assertFailsWith<ResponseStatusException> {
                service().requireFolder("alice", "f1", "alice")
            }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
        }
    }

    @Nested
    inner class TreeHelpers {

        @Test
        fun `resolvePath walks the display path inside one bucket`() = runTest {
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns listOf(
                folder("f1", name = "Family"),
                folder("f2", parentId = "f1", name = "Recipes"),
            )

            assertEquals("f2", service().resolvePath("alice", "Family/Recipes")?.id)
            assertNull(service().resolvePath("alice", "Family/Missing"))
        }

        @Test
        fun `ensureChild returns an existing child or creates one`() = runTest {
            val parent = folder("f1", name = "Family")
            coEvery { folderStore.listByBuckets(listOf("alice")) } returns
                listOf(parent, folder("c1", parentId = "f1", name = "Recipes"))

            assertEquals("c1", service().ensureChild("alice", parent, "Recipes", "alice").id)

            val saved = slot<KnowledgeFolder>()
            val created = service().ensureChild("alice", parent, "Photos", "alice")
            assertEquals("Photos", created.name)
            assertEquals("f1", created.parentId)
            coVerify { folderStore.save(capture(saved)) }
        }
    }

    @Nested
    inner class ListFolders {

        @Test
        fun `listing is empty when the caller cannot view`() = runTest {
            val deny = object : KnowledgeAccessPolicy {
                override suspend fun canView(context: KnowledgeAccessContext) = false
                override suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
                override suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle) = true
            }

            assertTrue(service(policy = deny).list(context()).isEmpty())
        }

        @Test
        fun `listing flags manageability per bucket`() = runTest {
            coEvery { folderStore.listByBuckets(any()) } returns listOf(
                folder("mine", userId = "alice"),
                folder("grp", userId = "grp-1"),
            )

            val result = service(sharedWithinGroup = true).list(context(groupUserId = "grp-1"))

            assertEquals(2, result.size)
            assertTrue(result.all { it.canManage })
        }
    }
}
