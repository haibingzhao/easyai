package com.easy.easyai.repository.knowledge

import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeFolder
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcKnowledgeFolderStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///knowledge_folder_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun folder(
        id: String,
        name: String,
        parentId: String? = null,
        isDefault: Boolean = false,
        userId: String = "alice",
    ) = KnowledgeFolder(
        id = id,
        userId = userId,
        parentId = parentId,
        name = name,
        isDefault = isDefault,
        createdBy = userId,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Nested
    inner class SaveAndRead {

        @Test
        fun `root folders round-trip with a null parent`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("root-1", "Family", userId = "alice-folder"))

            val row = store.findById("root-1")!!

            assertEquals("Family", row.name)
            assertNull(row.parentId)
        }

        @Test
        fun `child folders keep their parent`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("p-1", "Family", userId = "alice-child"))
            store.save(folder("c-1", "Recipes", parentId = "p-1", userId = "alice-child"))

            assertEquals("p-1", store.findById("c-1")?.parentId)
        }

        @Test
        fun `findDefault only returns the default row of that bucket`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("def-a", "未分类", isDefault = true, userId = "alice-default"))
            store.save(folder("other-a", "Family", userId = "alice-default"))
            store.save(folder("def-b", "未分类", isDefault = true, userId = "bob-default"))

            assertEquals("def-a", store.findDefault("alice-default")?.id)
            assertEquals("def-b", store.findDefault("bob-default")?.id)
            assertNull(store.findDefault("nobody"))
        }

        @Test
        fun `listByBuckets groups by owner`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("l-a", "A", userId = "alice-list"))
            store.save(folder("l-b", "B", userId = "bob-list"))

            val rows = store.listByBuckets(listOf("alice-list"))

            assertEquals(listOf("l-a"), rows.map { it.id })
        }
    }

    @Nested
    inner class Constraints {

        @Test
        fun `the same name is rejected under one parent`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("dup-1", "Recipes", userId = "alice-dup"))

            val failure = runCatching { store.save(folder("dup-2", "Recipes", userId = "alice-dup")) }

            assertTrue(failure.isFailure)
        }

        @Test
        fun `the same name is allowed under different parents`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("p1", "Family", userId = "alice-sibling"))
            store.save(folder("p2", "Work", userId = "alice-sibling"))
            store.save(folder("c1", "Notes", parentId = "p1", userId = "alice-sibling"))
            store.save(folder("c2", "Notes", parentId = "p2", userId = "alice-sibling"))

            assertNotNull(store.findById("c1"))
            assertNotNull(store.findById("c2"))
        }
    }

    private fun article(id: String, folderId: String, userId: String) = KnowledgeArticle(
        id = id,
        userId = userId,
        createdBy = userId,
        folderId = folderId,
        title = id,
        description = null,
        category = "other",
        ext = "md",
        origin = "upload",
        status = ArticleStatus.PUBLISHED,
        ragEnabled = true,
        createdAt = 1L,
        updatedAt = 1L,
    )

    @Nested
    inner class Delete {

        @Test
        fun `delete removes the row`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("gone", "Temp", userId = "alice-del"))

            store.delete("gone")

            assertNull(store.findById("gone"))
        }
    }

    @Nested
    inner class DeleteIfEmpty {

        @Test
        fun `deletes a folder with no children and no articles`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("free", "Temp", userId = "alice-empty"))

            assertTrue(store.deleteIfEmpty("free", listOf("free")))
            assertNull(store.findById("free"))
        }

        @Test
        fun `refuses a folder that still has a child`() = runTest {
            val store = R2dbcKnowledgeFolderStore(db)
            store.save(folder("p-keep", "Family", userId = "alice-child-del"))
            store.save(folder("k-keep", "Recipes", parentId = "p-keep", userId = "alice-child-del"))

            assertFalse(store.deleteIfEmpty("p-keep", listOf("p-keep", "k-keep")))
            assertNotNull(store.findById("p-keep"))
        }

        @Test
        fun `refuses a folder whose subtree still holds an article`() = runTest {
            val folderStore = R2dbcKnowledgeFolderStore(db)
            val articleStore = R2dbcKnowledgeArticleStore(db)
            folderStore.save(folder("p-held", "Family", userId = "alice-article-del"))
            folderStore.save(folder("k-held", "Recipes", parentId = "p-held", userId = "alice-article-del"))
            articleStore.save(article("held", folderId = "k-held", userId = "alice-article-del"))

            assertFalse(folderStore.deleteIfEmpty("p-held", listOf("p-held", "k-held")))
            assertNotNull(folderStore.findById("p-held"))
        }
    }
}
