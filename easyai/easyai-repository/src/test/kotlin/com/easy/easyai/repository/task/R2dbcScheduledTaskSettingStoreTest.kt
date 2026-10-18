package com.easy.easyai.repository.task

import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.database.Tables.ScheduledTaskSettingTable
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.insert
import org.jetbrains.exposed.v1.r2dbc.selectAll
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
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

/**
 * Integration tests for [R2dbcScheduledTaskSettingStore] over in-memory H2: the `(user, type)` composite
 * key, strict per-user isolation, opaque `params` JSON round-trip and the scan query's filters.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcScheduledTaskSettingStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///scheduledsetting_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore(): ScheduledTaskSettingStore = R2dbcScheduledTaskSettingStore(db)

    private fun setting(
        userId: String = "alice",
        taskType: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION,
        enabled: Boolean = true,
        cron: String? = "0 0 3 * * *",
        params: Map<String, Any?> = mapOf("typeThresholds" to mapOf("experience_lessons" to 7))
    ) = ScheduledTaskSetting(
        userId = userId,
        taskType = taskType,
        enabled = enabled,
        cron = cron,
        params = params
    )

    /** Write a row whose type this build does not know, bypassing the enum. */
    private suspend fun insertRawType(userId: String, typeKey: String, enabled: Boolean) = suspendTransaction(db) {
        ScheduledTaskSettingTable.insert {
            it[ScheduledTaskSettingTable.userId] = userId
            it[ScheduledTaskSettingTable.taskType] = typeKey
            it[ScheduledTaskSettingTable.enabled] = enabled
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    private suspend fun insertRawParams(userId: String, params: String) = suspendTransaction(db) {
        ScheduledTaskSettingTable.insert {
            it[ScheduledTaskSettingTable.userId] = userId
            it[ScheduledTaskSettingTable.taskType] = AsyncTaskType.MEMORY_CONSOLIDATION.key
            it[ScheduledTaskSettingTable.enabled] = true
            it[ScheduledTaskSettingTable.params] = params
            it[updatedAt] = System.currentTimeMillis()
        }
    }

    /** JSON comes back as whatever number type the parser chose, so compare numerically, not as `Int`. */
    private fun number(value: Any?): Int? = (value as? Number)?.toInt()

    @Nested
    inner class `save and read` {

        @Test
        fun `save round-trips cron and params for the composite key`() = runTest {
            val store = createStore()
            store.save(setting(userId = "roundtrip", cron = "0 15 4 * * *"))

            val loaded = assertNotNull(store.get("roundtrip", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals("0 15 4 * * *", loaded.cron)
            assertTrue(loaded.enabled)
            assertEquals(7, number((loaded.params["typeThresholds"] as? Map<*, *>)?.get("experience_lessons")))
        }

        @Test
        fun `a second save replaces the row instead of adding one`() = runTest {
            val store = createStore()
            val user = "upsert-user"
            store.save(setting(userId = user, enabled = true, params = mapOf("a" to 1)))
            store.save(setting(userId = user, enabled = false, params = mapOf("a" to 2)))

            val loaded = assertNotNull(store.get(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertFalse(loaded.enabled)
            assertEquals(2, number(loaded.params["a"]))

            val rows = suspendTransaction(db) {
                ScheduledTaskSettingTable.selectAll()
                    .where { ScheduledTaskSettingTable.userId eq user }
                    .count()
            }
            assertEquals(1L, rows)
        }

        @Test
        fun `one user can hold a row per task type`() = runTest {
            val store = createStore()
            val user = "multi-type"
            store.save(setting(userId = user, params = mapOf("typeThresholds" to mapOf("experience_lessons" to 3))))
            // A second known type does not exist yet, so the sibling key is written raw: what is under
            // test is that the primary key is (user, type) and not user alone.
            insertRawType(user, "some_future_type", enabled = true)

            val loaded = assertNotNull(store.get(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals(
                3,
                number((loaded.params["typeThresholds"] as? Map<*, *>)?.get("experience_lessons")),
                "the consolidation row must survive a sibling row for another type"
            )
            assertEquals(2L, storeRowsFor(user))
        }

        @Test
        fun `empty params stores no JSON and reads back empty`() = runTest {
            val store = createStore()
            store.save(setting(userId = "no-params", params = emptyMap()))

            val loaded = assertNotNull(store.get("no-params", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals(emptyMap(), loaded.params)
        }

        @Test
        fun `get returns null for a user who never configured the type`() = runTest {
            assertNull(createStore().get("absent", AsyncTaskType.MEMORY_CONSOLIDATION))
        }
    }

    @Nested
    inner class `isolation` {

        @Test
        fun `get cannot read another user's row`() = runTest {
            val store = createStore()
            store.save(setting(userId = "owner-a"))

            assertNull(store.get("owner-b", AsyncTaskType.MEMORY_CONSOLIDATION))
        }

        @Test
        fun `delete only removes the named type of the named user`() = runTest {
            val store = createStore()
            val user = "delete-user"
            store.save(setting(userId = user))

            assertTrue(store.delete(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertFalse(store.delete(user, AsyncTaskType.MEMORY_CONSOLIDATION))
            assertNull(store.get(user, AsyncTaskType.MEMORY_CONSOLIDATION))
        }
    }

    @Nested
    inner class `enabled scan` {

        @Test
        fun `listEnabled skips disabled rows`() = runTest {
            val store = createStore()
            store.save(setting(userId = "scan-on", enabled = true))
            store.save(setting(userId = "scan-off", enabled = false))

            val enabled = store.listEnabled()
            assertTrue(enabled.any { it.userId == "scan-on" })
            assertFalse(enabled.any { it.userId == "scan-off" })
        }

        @Test
        fun `listEnabled narrows to the requested type`() = runTest {
            val store = createStore()
            val user = "filter-user"
            store.save(setting(userId = user))
            // An enabled row for a type this build cannot name must not reach the filtered scan — and it
            // really is enabled, so an absent result means the filter worked rather than the row being off.
            insertRawType(user, "some_future_type", enabled = true)

            val filtered = store.listEnabled(AsyncTaskType.MEMORY_CONSOLIDATION)
            assertEquals(1, filtered.count { it.userId == user })
            assertTrue(filtered.all { it.taskType == AsyncTaskType.MEMORY_CONSOLIDATION })
        }

        @Test
        fun `an unknown task type is skipped, not returned`() = runTest {
            insertRawType("ghost-user", "no_such_type", enabled = true)

            val store = createStore()
            assertFalse(store.listEnabled().any { it.userId == "ghost-user" })
            assertNull(store.get("ghost-user", AsyncTaskType.MEMORY_CONSOLIDATION))
        }

        @Test
        fun `unparsable params degrade to empty rather than failing the scan`() = runTest {
            insertRawParams("broken-params", "{not json")

            val loaded = assertNotNull(createStore().get("broken-params", AsyncTaskType.MEMORY_CONSOLIDATION))
            assertEquals(emptyMap(), loaded.params)
        }
    }

    private suspend fun storeRowsFor(userId: String): Long = suspendTransaction(db) {
        ScheduledTaskSettingTable.selectAll()
            .where { ScheduledTaskSettingTable.userId eq userId }
            .mapNotNull { it[ScheduledTaskSettingTable.userId] }
            .toList()
            .size
            .toLong()
    }
}
