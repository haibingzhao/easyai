package com.easy.easyai.repository.skill

import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.skill.SkillSyncUpdate
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [R2dbcAsyncSkillCatalogStore] against an in-memory H2 (MODE=MYSQL) database.
 *
 * The interesting cases here are all about identity: rows are addressed by the `(user_id, name)`
 * pair, so the same skill name of two owners — or of an owner and the `system` shared layer — must
 * survive as two rows, and every mutation must land on exactly one primary key. A write that lands
 * on another owner's row is a tenant-isolation bug, not a display glitch.
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
    ) = SkillCatalogEntry(
        name = name,
        source = source,
        version = "1.2.3",
        checksum = checksum,
        enabled = enabled,
        rootPath = "/home/$userId/.easyai/skills/$userId",
        installPath = "/home/$userId/.easyai/skills/$userId/$name",
        objectKey = "skills/$userId/$name.zip",
        userId = userId,
    )

    private fun uniqueUser(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    @Nested
    inner class `claim and content update semantics` {

        @Test
        fun `a second claim of the same name leaves the existing row untouched`() = runTest {
            val store = createStore()
            val user = uniqueUser("claim")
            val original = entry("pdf-report", user, enabled = false)
            val first = store.claim(original)
            val second = store.claim(original.copy(
                checksum = "c".repeat(64), version = "2.0.0", enabled = true,
                installPath = "/somewhere/else/.easyai/skills/$user/pdf-report",
                objectKey = "skills/other/pdf-report.zip"
            ))

            assertEquals(first, second, "claim must not overwrite content, ownership, path or enablement")
            assertEquals(first, store.listByUser(user).single())
            assertEquals(original.userId, second.userId)
            assertEquals(original.installPath, second.installPath)
            assertEquals(original.objectKey, second.objectKey)
            assertFalse(second.enabled)
        }

        @Test
        fun `createdAt is kept and updatedAt moves on a content update`() = runTest {
            val store = createStore()
            val user = uniqueUser("stamps")

            val created = store.claim(entry("timed", user))
            assertTrue(store.updateContent(created.id, created.revision, "d".repeat(64), "2.0.0"))
            val updated = assertNotNull(store.findById(created.id))

            assertTrue(created.createdAt > 0)
            assertEquals(created.createdAt, updated.createdAt)
            assertTrue(updated.updatedAt >= created.updatedAt)
            assertEquals(created.revision + 1, updated.revision)
        }

        @Test
        fun `the same name owned by two users coexists`() = runTest {
            val store = createStore()
            val alice = uniqueUser("alice")
            val bob = uniqueUser("bob")

            store.claim(entry("foo", alice, checksum = "e".repeat(64)))
            store.claim(entry("foo", bob, checksum = "f".repeat(64)))

            assertEquals("e".repeat(64), store.findByName(alice, "foo")?.checksum)
            assertEquals("f".repeat(64), store.findByName(bob, "foo")?.checksum)
            assertEquals(1, store.listByUser(alice).size)
            assertEquals(1, store.listByUser(bob).size)
        }

        @Test
        fun `an owner row and a shared row of one name are two rows that never overwrite each other`() = runTest {
            val store = createStore()
            val user = uniqueUser("shadow")

            val first = store.claim(entry("pdf", user, checksum = "a".repeat(64)))
            val second = store.claim(entry("pdf", SkillCatalogEntry.DEFAULT_USER_ID, checksum = "b".repeat(64)))
            assertTrue(store.updateContent(first.id, first.revision, "c".repeat(64), "2.0.0"))

            val rows = store.listByOwners(listOf(user, SkillCatalogEntry.DEFAULT_USER_ID))
                .filter { it.name == "pdf" }
            assertEquals(2, rows.size, "the unique index is (user_id, name) — the shared layer is a distinct row")
            val updated = rows.first { it.id == first.id }
            assertEquals("c".repeat(64), updated.checksum)
            assertEquals(first.rootPath, updated.rootPath)
            assertEquals(second, rows.first { it.id == second.id })
        }

        // A unique violation must retry in a fresh transaction without changing the winning claim.
        @Test
        fun `concurrent claims of one name converge without overwriting the winner`() = runTest {
            val store = createStore()
            val user = uniqueUser("race")
            val results = coroutineScope {
                (1..8).map { i ->
                    async(Dispatchers.IO) {
                        store.claim(entry("race", user, checksum = "$i".repeat(64)))
                    }
                }.map { it.await() }
            }

            val rows = store.listByUser(user)
            assertEquals(1, rows.size, "a unique-index collision must return the existing row")
            assertTrue(results.all { it.name == "race" && it.userId == user })
            assertTrue(results.all { it == rows.single() }, "every racer must observe the unchanged winning claim")
        }
    }

    @Nested
    inner class `recoverable synchronization CAS` {
        @Test
        fun `claims are pending and a conflicting claim never resets disabled ownership`() = runTest {
            val store = createStore()
            val user = uniqueUser("claim")
            val row = store.claim(entry("pdf", user, enabled = false))
            assertEquals(SkillSyncState.PENDING_DELETE, row.syncState)
            assertNull(row.indexedChecksum)
            val claimed = store.claim(entry("pdf", user, enabled = true, checksum = "b".repeat(64)))
            assertEquals(row, claimed)
            val pending = store.claim(entry("other", user))
            assertEquals(SkillSyncState.PENDING_INDEX, pending.syncState)
        }

        @Test
        fun `late completion and stale content CAS cannot overwrite concurrent disable`() = runTest {
            val store = createStore()
            val row = store.claim(entry("pdf", uniqueUser("cas")))
            assertTrue(store.setEnabled(row.id, false))
            assertFalse(store.updateContent(row.id, row.revision, "b".repeat(64), "2.0.0"))
            assertFalse(store.updateSync(row.id, row.revision, SkillSyncUpdate(SkillSyncState.SYNCED, row.checksum)))
            val current = store.findById(row.id)!!
            assertFalse(current.enabled)
            assertEquals(row.userId, current.userId)
            assertEquals(row.checksum, current.checksum)
            assertEquals(SkillSyncState.PENDING_DELETE, current.syncState)
            assertTrue(store.updateSync(current.id, current.revision,
                SkillSyncUpdate(SkillSyncState.PENDING_DELETE, null, 123L, "offline")))
            val failed = store.findById(row.id)!!
            assertEquals(123L, failed.nextAttemptAt)
            assertEquals("offline", failed.lastError)
            assertTrue(store.updateSync(failed.id, failed.revision, SkillSyncUpdate(SkillSyncState.ABSENT, null)))
        }

        @Test
        fun `observed checksum is separate from confirmed checksum and wrong target is refused`() = runTest {
            val store = createStore()
            val row = store.claim(entry("pdf", uniqueUser("observed")))
            assertFalse(store.updateSync(row.id, row.revision, SkillSyncUpdate(SkillSyncState.SYNCED, "wrong")))
            assertTrue(store.updateSync(row.id, row.revision, SkillSyncUpdate(SkillSyncState.SYNCED, row.checksum)))
            val indexed = store.findById(row.id)!!
            assertTrue(store.updateContent(indexed.id, indexed.revision, "b".repeat(64), "2.0.0"))
            val changed = store.findById(row.id)!!
            assertEquals(row.checksum, changed.indexedChecksum)
            assertEquals("b".repeat(64), changed.checksum)
            assertEquals(SkillSyncState.PENDING_INDEX, changed.syncState)
        }

        @Test
        fun `content and sync updates preserve disabled ownership`() = runTest {
            val store = createStore()
            val row = store.claim(entry("pdf", uniqueUser("disabled"), enabled = false))
            assertTrue(store.updateContent(row.id, row.revision, "b".repeat(64), "2.0.0"))
            val updated = assertNotNull(store.findById(row.id))
            assertEquals(row.copy(
                checksum = "b".repeat(64), version = "2.0.0", revision = row.revision + 1, updatedAt = updated.updatedAt
            ), updated, "content updates must preserve owner, root path, install path, package key and enablement")
            assertFalse(store.updateSync(updated.id, updated.revision,
                SkillSyncUpdate(SkillSyncState.SYNCED, updated.checksum)))
            assertFalse(store.updateSync(updated.id, updated.revision,
                SkillSyncUpdate(SkillSyncState.SUBMITTED, updated.checksum)))
            assertTrue(store.updateSync(updated.id, updated.revision, SkillSyncUpdate(SkillSyncState.ABSENT, null)))
            val completed = assertNotNull(store.findById(row.id))
            assertEquals(updated.copy(
                syncState = SkillSyncState.ABSENT, revision = updated.revision + 1, updatedAt = completed.updatedAt
            ), completed, "sync completion must only change projection fields")
        }
    }

    @Nested
    inner class `PostgreSQL transaction recovery` {
        @Test
        fun `concurrent PostgreSQL claims retry whole transactions after unique violations`() = runTest {
            val url = System.getenv("EASYAI_TEST_POSTGRES_R2DBC_URL")
            assumeTrue(!url.isNullOrBlank(), "Requires a dedicated external PostgreSQL test database")
            val postgres = R2dbcDatabase.connect(requireNotNull(url), manager = { TransactionManager(it) })
            DatabaseMigration.defaultTables().execute(postgres)
            val store = R2dbcAsyncSkillCatalogStore(postgres)
            val user = uniqueUser("pg-claim")
            try {
                val results = coroutineScope {
                    (1..8).map { async(Dispatchers.IO) { store.claim(entry("race", user)) } }.map { it.await() }
                }
                assertEquals(1, results.map { it.id }.distinct().size)
                val row = store.listByUser(user).single()
                assertTrue(store.setEnabled(row.id, false))
                assertFalse(store.updateSync(row.id, row.revision, SkillSyncUpdate(SkillSyncState.SYNCED, row.checksum)))
            } finally {
                store.listByUser(user).forEach { store.delete(it.id) }
            }
        }
    }

    @Nested
    inner class `owner enumeration` {

        @Test
        fun `listDistinctUserIds collapses rows of the same owner`() = runTest {
            val store = createStore()
            val user = uniqueUser("multi")

            store.claim(entry("one", user))
            store.claim(entry("two", user))

            val owners = store.listDistinctUserIds()
            assertEquals(owners.distinct().size, owners.size, "owners must be deduplicated")
            assertTrue(user in owners)
        }

        @Test
        fun `listByOwners returns every row of the requested owners`() = runTest {
            val store = createStore()
            val user = uniqueUser("owners")
            val stranger = uniqueUser("owners-stranger")

            store.claim(entry("own", user))
            val shared = store.claim(entry("shared", SkillCatalogEntry.DEFAULT_USER_ID))
            store.claim(entry("stranger", stranger))

            val rows = store.listByOwners(listOf(user, SkillCatalogEntry.DEFAULT_USER_ID))
            assertTrue(rows.any { it.userId == user && it.name == "own" })
            assertTrue(shared in rows)
            assertTrue(rows.none { it.userId == stranger }, "unrequested owners stay out of the result")
            assertTrue(store.listByOwners(emptyList()).isEmpty())
        }
    }

    @Nested
    inner class `strict owner filtering` {

        @Test
        fun `setEnabled cannot touch a row the caller does not hold the id of`() = runTest {
            val store = createStore()
            val owner = uniqueUser("owner")
            store.claim(entry("locked", owner))

            assertFalse(store.setEnabled("no-such-row-${UUID.randomUUID()}", false))
            assertTrue(store.findByName(owner, "locked")?.enabled == true,
                "an unknown id must leave every row untouched")
        }

        @Test
        fun `setEnabled flips exactly the addressed row`() = runTest {
            val store = createStore()
            val user = uniqueUser("toggle")
            val row = store.claim(entry("switch", user))
            val shared = store.claim(entry("switch", SkillCatalogEntry.DEFAULT_USER_ID))

            assertTrue(store.setEnabled(row.id, false))
            assertFalse(store.findByName(user, "switch")!!.enabled)
            assertTrue(
                store.findByName(SkillCatalogEntry.DEFAULT_USER_ID, "switch")!!.enabled,
                "the shared layer keeps its own enablement when a user disables their shadowing row"
            )
            assertEquals(shared.revision, store.findByName(SkillCatalogEntry.DEFAULT_USER_ID, "switch")!!.revision)
        }

        @Test
        fun `delete removes only the addressed row`() = runTest {
            val store = createStore()
            val alice = uniqueUser("del-alice")
            val bob = uniqueUser("del-bob")
            val aliceRow = store.claim(entry("shared", alice))
            store.claim(entry("shared", bob))

            assertTrue(store.delete(aliceRow.id))
            assertNull(store.findByName(alice, "shared"))
            assertNotNull(store.findByName(bob, "shared"), "the other owner keeps their skill")
            assertFalse(store.delete(aliceRow.id), "deleting an absent row says so")
        }

        @Test
        fun `updateContent targets the row by id and rejects stale revisions`() = runTest {
            val store = createStore()
            val user = uniqueUser("checksum")
            val row = store.claim(entry("drifty", user))
            val otherOwner = store.claim(entry("drifty", uniqueUser("other-owner")))

            assertTrue(store.updateContent(row.id, row.revision, "9".repeat(64), "2.0.0"))
            val reloaded = store.findByName(user, "drifty")
            assertNotNull(reloaded)
            assertEquals("9".repeat(64), reloaded.checksum)
            assertEquals("2.0.0", reloaded.version)
            assertEquals(row.revision + 1, reloaded.revision)
            assertEquals(row.userId, reloaded.userId)
            assertEquals(row.installPath, reloaded.installPath)
            assertEquals(row.enabled, reloaded.enabled)
            assertFalse(store.updateContent(row.id, row.revision, "1".repeat(64), "0.0.1"))
            assertFalse(store.updateContent(UUID.randomUUID().toString(), 0L, "1".repeat(64), "0.0.1"))
            assertEquals(reloaded, store.findById(row.id))
            assertEquals(otherOwner, store.findById(otherOwner.id))
        }
    }
}
