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

/**
 * Integration tests for [R2dbcKnowledgeArticleStore] over an in-memory H2 R2DBC database:
 * status column round-trip and the status filter used by the article list.
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
        knowledgeKey: String,
        status: ArticleStatus,
        userId: String = "alice",
    ) = KnowledgeArticle(
        id = id,
        userId = userId,
        createdBy = userId,
        knowledgeKey = knowledgeKey,
        ossKey = "knowledge-articles/$userId/$knowledgeKey",
        title = knowledgeKey,
        description = null,
        category = "other",
        source = knowledgeKey.substringBefore('/'),
        relativePath = knowledgeKey.substringAfter('/'),
        ext = "md",
        contentHash = null,
        origin = "manual",
        originSessionId = null,
        originMessageId = null,
        composedFrom = null,
        status = status,
        createdAt = 1L,
        updatedAt = 2L,
    )

    @Nested
    inner class FindAllInBuckets {

        @Test
        fun `status filter narrows to one lifecycle state`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("draft-1", "manual/draft.md", ArticleStatus.DRAFT, userId = "alice-list"))
            store.save(article("published-1", "manual/published.md", ArticleStatus.PUBLISHED, userId = "alice-list"))
            store.save(article("draft-2", "manual/other.md", ArticleStatus.DRAFT, userId = "bob-list"))

            val drafts = store.findAllInBuckets(listOf("alice-list"), status = ArticleStatus.DRAFT).toList()
            val published = store.findAllInBuckets(listOf("alice-list"), status = ArticleStatus.PUBLISHED).toList()
            val all = store.findAllInBuckets(listOf("alice-list")).toList()

            assertEquals(listOf("draft-1"), drafts.map { it.id })
            assertEquals(listOf("published-1"), published.map { it.id })
            assertEquals(setOf("draft-1", "published-1"), all.map { it.id }.toSet())
        }

        @Test
        fun `status survives a save round-trip`() = runTest {
            val store = R2dbcKnowledgeArticleStore(db)
            store.save(article("draft-2", "manual/roundtrip.md", ArticleStatus.DRAFT, userId = "alice-roundtrip"))

            assertEquals(
                ArticleStatus.DRAFT,
                store.findByKey("alice-roundtrip", "manual/roundtrip.md")?.status,
            )
        }
    }
}
