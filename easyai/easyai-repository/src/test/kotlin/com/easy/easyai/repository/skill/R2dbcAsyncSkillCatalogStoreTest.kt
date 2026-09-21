package com.easy.easyai.repository.skill

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import kotlin.test.assertTrue

/**
 * Tests for [R2dbcAsyncSkillCatalogStore] against an in-memory H2 (MODE=MYSQL) database.
 *
 * The interesting cases here are all about identity: rows are addressed by the `(user_id, name,
 * project_hash)` triple, so a same-named skill of two projects must survive as two rows, and every
 * mutation must land on exactly one primary key — a cross-granularity write is a tenant-isolation
 * bug, not a display glitch.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcAsyncSkillCatalogStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///skill_catalog_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore() = R2dbcAsyncSkillCatalogStore(db)

    private fun entry(
        name: String,
        userId: String,
        checksum: String = "a".repeat(64),
        enabled: Boolean = true,
        source: String = SkillCatalogEntry.SOURCE_LOCAL,
        projectHash: String = SkillCatalogEntry.GLOBAL_HASH
    ) = SkillCatalogEntry(
        name = name,
        source = source,
        version = "1.2.3",
        checksum = checksum,
        enabled = enabled,
        installPath = "/home/$userId/.easyai/skills/$name",
        origin = null,
        userId = userId,
        projectHash = projectHash
    )

    private fun uniqueUser(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    @Nested
    inner class `upsert semantics` {

        @Test
        fun `a second write of the same triple updates instead of inserting`() = runTest {
            val store = createStore()
            val user = uniqueUser("upsert")

            val first = store.upsert(entry("pdf-report", user, checksum = "b".repeat(64)))
            val second = store.upsert(entry("pdf-report", user, checksum = "c".repeat(64), source = "EXTERNAL"))

            assertEquals(first.id, second.id, "the row identity must survive an update")
            assertEquals(1, store.listByUser(user).size)
            assertEquals("c".repeat(64), store.listByName("pdf-report", user).single().checksum)
            assertEquals("EXTERNAL", store.listByName("pdf-report", user).single().source)
        }

        @Test
        fun `createdAt is kept and updatedAt moves on the second write`() = runTest {
            val store = createStore()
            val user = uniqueUser("stamps")

            val created = store.upsert(entry("timed", user))
            val updated = store.upsert(entry("timed", user, checksum = "d".repeat(64)))

            assertTrue(created.createdAt > 0)
            assertEquals(created.createdAt, updated.createdAt)
            assertTrue(updated.updatedAt >= created.updatedAt)
        }

        @Test
        fun `the same name owned by two users coexists`() = runTest {
            val store = createStore()
            val alice = uniqueUser("alice")
            val bob = uniqueUser("bob")

            store.upsert(entry("foo", alice, checksum = "e".repeat(64)))
            store.upsert(entry("foo", bob, checksum = "f".repeat(64)))

            assertEquals("e".repeat(64), store.listByName("foo", alice).single().checksum)
            assertEquals("f".repeat(64), store.listByName("foo", bob).single().checksum)
            assertEquals(1, store.listByUser(alice).size)
            assertEquals(1, store.listByUser(bob).size)
        }

        @Test
        fun `the same name and owner under two projects are two rows that never overwrite each other`() = runTest {
            val store = createStore()
            val user = uniqueUser("tri")

            store.upsert(entry("pdf", user, checksum = "a".repeat(64), projectHash = "hash-a"))
            store.upsert(entry("pdf", user, checksum = "b".repeat(64), projectHash = "hash-b"))
            // Third write hits the first triple again: it must update hash-a's row, not insert a fourth.
            store.upsert(entry("pdf", user, checksum = "c".repeat(64), projectHash = "hash-a"))

            val rows = store.listByName("pdf", user)
            assertEquals(2, rows.size, "the unique index is (user, name, project_hash) — two granularities, two rows")
            assertEquals("c".repeat(64), rows.first { it.projectHash == "hash-a" }.checksum)
            assertEquals("b".repeat(64), rows.first { it.projectHash == "hash-b" }.checksum)
        }

        // H2: the store's SELECT-then-INSERT is not atomic. Two concurrent upserts of the same triple
        // can both read "no row" and both attempt INSERT; the V6 unique index rejects one of them. The
        // store catches that unique violation and retries as an UPDATE — otherwise `backfillAll` at
        // startup silently loses rows whenever two owner enumerations race.
        @Test
        fun `concurrent upserts of the same triple converge on one row, not a unique-violation error`() = runTest {
            val store = createStore()
            val user = uniqueUser("race")
            val results = coroutineScope {
                (1..8).map { i ->
                    async(Dispatchers.IO) {
                        store.upsert(entry("race", user, checksum = "$i".repeat(64)))
                    }
                }.map { it.await() }
            }

            val rows = store.listByName("race", user)
            assertEquals(1, rows.size, "a unique-index collision must degrade to an UPDATE, not to a lost row")
            assertTrue(results.all { it.name == "race" && it.userId == user })
            assertEquals(rows.single().id, results.map { it.id }.distinct().single(),
                "every racer must converge on the same row identity")
        }
    }

    @Nested
    inner class `owner enumeration` {

        @Test
        fun `listDistinctUserIds collapses rows of the same owner`() = runTest {
            val store = createStore()
            val user = uniqueUser("multi")

            store.upsert(entry("one", user))
            store.upsert(entry("two", user))

            val owners = store.listDistinctUserIds()
            assertEquals(owners.distinct().size, owners.size, "owners must be deduplicated")
            assertTrue(user in owners)
        }
    }

    @Nested
    inner class `strict owner filtering` {

        @Test
        fun `setEnabled cannot touch a row the caller does not hold the id of`() = runTest {
            val store = createStore()
            val owner = uniqueUser("owner")
            store.upsert(entry("locked", owner))

            assertFalse(store.setEnabled("no-such-row-${UUID.randomUUID()}", false))
            assertTrue(store.listByName("locked", owner).single().enabled, "an unknown id must leave every row untouched")
        }

        @Test
        fun `setEnabled flips exactly the addressed row`() = runTest {
            val store = createStore()
            val user = uniqueUser("toggle")
            val row = store.upsert(entry("switch", user))
            val sibling = store.upsert(entry("switch", user, projectHash = "other-project"))

            assertTrue(store.setEnabled(row.id, false))
            assertFalse(store.listByName("switch", user).first { it.id == row.id }.enabled)
            assertTrue(
                store.listByName("switch", user).first { it.id == sibling.id }.enabled,
                "the same-named row of another project must not flip with it"
            )
        }

        @Test
        fun `delete removes only the addressed row`() = runTest {
            val store = createStore()
            val alice = uniqueUser("del-alice")
            val bob = uniqueUser("del-bob")
            val aliceRow = store.upsert(entry("shared", alice))
            store.upsert(entry("shared", bob))

            assertTrue(store.delete(aliceRow.id))
            assertTrue(store.listByName("shared", alice).isEmpty())
            assertNotNull(store.listByName("shared", bob).singleOrNull(), "the other owner keeps their skill")
            assertFalse(store.delete(aliceRow.id), "deleting an absent row says so")
        }

        @Test
        fun `updateChecksum targets the row by id`() = runTest {
            val store = createStore()
            val user = uniqueUser("checksum")
            val row = store.upsert(entry("drifty", user))

            assertTrue(store.updateChecksum(row.id, "9".repeat(64), "2.0.0"))
            val reloaded = store.listByName("drifty", user).single()
            assertEquals("9".repeat(64), reloaded.checksum)
            assertEquals("2.0.0", reloaded.version)
            assertFalse(store.updateChecksum(UUID.randomUUID().toString(), "1".repeat(64), "0.0.1"))
        }
    }
}
