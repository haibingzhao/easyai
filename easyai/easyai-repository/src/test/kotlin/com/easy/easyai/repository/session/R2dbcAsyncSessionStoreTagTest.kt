package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for session multi-tag persistence in [R2dbcAsyncSessionStore]:
 * updateTags / findTagsMap / findAllTags, OR filtering in findMetadataByLimit,
 * normalization, and cascade cleanup on delete.
 */
class R2dbcAsyncSessionStoreTagTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///session_tag_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val store = R2dbcAsyncSessionStore(db)

    private suspend fun createSession(sessionId: String, userId: String = "system", projectId: String? = null) {
        store.save(
            PersistedSession(
                id = sessionId,
                messages = emptyList(),
                projectId = projectId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            ),
            userId
        )
    }

    @Nested
    inner class `updateTags and findTagsMap` {

        @Test
        fun `round-trips tags and overwrites on update`() = runTest {
            val sid = "s-${UUID.randomUUID()}"
            createSession(sid)

            store.updateTags(sid, listOf("alpha", "beta"))
            assertEquals(listOf("alpha", "beta"), store.findTagsMap(listOf(sid))[sid])

            // Overwrite (not merge)
            store.updateTags(sid, listOf("gamma"))
            assertEquals(listOf("gamma"), store.findTagsMap(listOf(sid))[sid])

            // Empty clears
            store.updateTags(sid, emptyList())
            assertTrue(store.findTagsMap(listOf(sid))[sid].isNullOrEmpty())
        }

        @Test
        fun `normalizes tags - trim, drop blanks, de-duplicate`() = runTest {
            val sid = "s-${UUID.randomUUID()}"
            createSession(sid)

            store.updateTags(sid, listOf("  dup ", "dup", "", "   ", "keep"))
            val tags = store.findTagsMap(listOf(sid))[sid] ?: emptyList()
            assertEquals(setOf("dup", "keep"), tags.toSet())
            assertEquals(2, tags.size)
        }
    }

    @Nested
    inner class `findAllTags` {

        @Test
        fun `aggregates distinct tags scoped by project`() = runTest {
            val project = "p-${UUID.randomUUID()}"
            val other = "p-${UUID.randomUUID()}"
            val a = "s-${UUID.randomUUID()}"
            val b = "s-${UUID.randomUUID()}"
            val c = "s-${UUID.randomUUID()}"
            createSession(a, projectId = project)
            createSession(b, projectId = project)
            createSession(c, projectId = other)

            store.updateTags(a, listOf("shared", "onlyA"))
            store.updateTags(b, listOf("shared"))
            store.updateTags(c, listOf("elsewhere"))

            val scoped = store.findAllTags(project)
            assertTrue("shared" in scoped)
            assertTrue("onlyA" in scoped)
            assertFalse("elsewhere" in scoped, "tags from other projects must be excluded")

            val all = store.findAllTags(null)
            assertTrue("elsewhere" in all)
        }
    }

    @Nested
    inner class `findMetadataByLimit tag filter` {

        @Test
        fun `OR filter matches sessions carrying any selected tag without substring false positives`() = runTest {
            val bug = "s-${UUID.randomUUID()}"
            val debug = "s-${UUID.randomUUID()}"
            val ui = "s-${UUID.randomUUID()}"
            val plain = "s-${UUID.randomUUID()}"
            listOf(bug, debug, ui, plain).forEach { createSession(it) }

            store.updateTags(bug, listOf("bug"))
            store.updateTags(debug, listOf("debug"))
            store.updateTags(ui, listOf("ui"))
            // plain has no tags

            val (metadata, _) = store.findMetadataByLimit(limit = 100, offset = 0, tags = listOf("bug", "ui"))
            val ids = metadata.map { it.id }

            assertTrue(bug in ids)
            assertTrue(ui in ids)
            assertFalse(debug in ids, "exact tag match must not hit 'debug' when filtering 'bug'")
            assertFalse(plain in ids)

            // Tags are backfilled onto the metadata rows
            val bugMeta = metadata.first { it.id == bug }
            assertEquals(listOf("bug"), bugMeta.tags)
        }
    }

    @Nested
    inner class `delete cascade` {

        @Test
        fun `deleting a session removes its tags`() = runTest {
            val sid = "s-${UUID.randomUUID()}"
            createSession(sid)
            store.updateTags(sid, listOf("temp"))
            assertEquals(listOf("temp"), store.findTagsMap(listOf(sid))[sid])

            store.delete(sid)
            assertTrue(store.findTagsMap(listOf(sid))[sid].isNullOrEmpty())
        }
    }
}
