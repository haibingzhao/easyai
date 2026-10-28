package com.easy.easyai.repository.knowledge

import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Integration tests for [R2dbcKnowledgeArticleStore] over an in-memory H2 R2DBC database:
 * the folder/file columns, the retrieval flag and the queries the service relies on.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcKnowledgeArticleStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///knowledge_article_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun article(
        id: String,
        folderId: String = "folder-1",
        fileName: String? = null,
        status: ArticleStatus = ArticleStatus.DRAFT,
        ragEnabled: Boolean = false,
        userId: String = "alice",
    ) = KnowledgeArticle(
        id = id,
        userId = userId,
        createdBy = userId,
        folderId = folderId,
        fileName = fileName,
        title = id,
        description = "desc",
        category = "other",
        ext = "md",
        origin = "manual",
        status = status,
        ragEnabled = ragEnabled,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Nested
    inner class FindAllInBuckets {

        @Test
        fun `status filter narrows to one lifecycle state`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("draft-1", status = ArticleStatus.DRAFT, userId = "alice-list"))
            store.save(article("published-1", status = ArticleStatus.PUBLISHED, userId = "alice-list"))
            store.save(article("draft-2", status = ArticleStatus.DRAFT, userId = "bob-list"))

            val drafts = store.findAllInBuckets(listOf("alice-list"), status = ArticleStatus.DRAFT).toList()
            val published = store.findAllInBuckets(listOf("alice-list"), status = ArticleStatus.PUBLISHED).toList()
            val all = store.findAllInBuckets(listOf("alice-list")).toList()

            assertEquals(listOf("draft-1"), drafts.map { it.id })
            assertEquals(listOf("published-1"), published.map { it.id })
            assertEquals(setOf("draft-1", "published-1"), all.map { it.id }.toSet())
        }

        @Test
        fun `folder and category filters narrow the list`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("f1-a", folderId = "f1", userId = "alice-filter"))
            store.save(article("f1-b", folderId = "f1", userId = "alice-filter"))
            store.save(article("f2-a", folderId = "f2", userId = "alice-filter"))

            val inFolder = store.findAllInBuckets(listOf("alice-filter"), folderId = "f1").toList()
            val wrongCategory = store.findAllInBuckets(listOf("alice-filter"), category = "recipe").toList()

            assertEquals(setOf("f1-a", "f1-b"), inFolder.map { it.id }.toSet())
            assertTrue(wrongCategory.isEmpty())
        }

        @Test
        fun `all columns survive a save round-trip`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(
                article(
                    "roundtrip",
                    folderId = "folder-rt",
                    fileName = "notes.md",
                    status = ArticleStatus.PUBLISHED,
                    ragEnabled = true,
                    userId = "alice-roundtrip",
                )
            )

            val row = store.findById("roundtrip")!!
            assertEquals("folder-rt", row.folderId)
            assertEquals("notes.md", row.fileName)
            assertEquals(ArticleStatus.PUBLISHED, row.status)
            assertTrue(row.ragEnabled)
        }
    }

    @Nested
    inner class Lookups {

        @Test
        fun `findByIds is scoped to one bucket`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("mine", userId = "alice-ids"))
            store.save(article("other", userId = "bob-ids"))

            val rows = store.findByIds("alice-ids", listOf("mine", "other"))

            assertEquals(listOf("mine"), rows.map { it.id })
        }

        @Test
        fun `findByFolderAndFileName matches the upload overwrite key`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("upload-1", folderId = "f1", fileName = "a.md", userId = "alice-up"))
            store.save(article("upload-2", folderId = "f2", fileName = "a.md", userId = "alice-up"))

            assertEquals("upload-1", store.findByFolderAndFileName("alice-up", "f1", "a.md")?.id)
            assertEquals("upload-2", store.findByFolderAndFileName("alice-up", "f2", "a.md")?.id)
            assertNull(store.findByFolderAndFileName("alice-up", "f1", "b.md"))
        }

        @Test
        fun `manual articles without a file name never match an upload`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("manual-1", folderId = "f1", fileName = null, userId = "alice-manual"))

            assertNull(store.findByFolderAndFileName("alice-manual", "f1", "a.md"))
        }

        @Test
        fun `unique file name per folder is enforced but manual rows are exempt`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("dup-1", folderId = "f-dup", fileName = "same.md", userId = "alice-dup"))
            val failure = runCatching {
                store.save(article("dup-2", folderId = "f-dup", fileName = "same.md", userId = "alice-dup"))
            }
            assertTrue(failure.isFailure)

            // Two manual articles in one folder are fine: NULL file names do not collide.
            store.save(article("manual-a", folderId = "f-dup", fileName = null, userId = "alice-dup"))
            store.save(article("manual-b", folderId = "f-dup", fileName = null, userId = "alice-dup"))
        }
    }
}
