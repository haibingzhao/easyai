package com.easy.easyai.repository.project

import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.core.model.ProjectKind
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

/**
 * Integration tests for the project `kind` column (V14).
 *
 * Temporary workspaces must stay invisible in user-facing lists while remaining fully
 * addressable by id and path — chat requests carry the temporary projectId, and
 * ChatStreamService resolves it through findById/findByPath.
 * Uses an in-memory H2 R2DBC database.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcAsyncProjectStoreKindTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///project_kind_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun createStore() = R2dbcAsyncProjectStore(db)

    private fun project(
        path: String,
        kind: ProjectKind,
        name: String = "p-${UUID.randomUUID()}"
    ) = ProjectInfo(
        id = UUID.randomUUID().toString(),
        name = name,
        path = path,
        kind = kind
    )

    @Nested
    inner class KindRoundTrip {
        @Test
        fun `save persists kind and reads it back`() = runTest {
            val store = createStore()
            val temp = project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP)
            store.save(temp, USER)

            val loaded = store.findById(temp.id, USER)
            assertEquals(ProjectKind.TEMP, loaded?.kind)
            assertEquals(temp.path, loaded?.path)
        }

        @Test
        fun `update never reclassifies a project`() = runTest {
            val store = createStore()
            val temp = project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP)
            store.save(temp, USER)

            // Callers rebuild the row from a request DTO that defaults kind to USER.
            store.save(temp.copy(name = "renamed"), USER)

            assertEquals(ProjectKind.TEMP, store.findById(temp.id, USER)?.kind)
        }
    }

    @Nested
    inner class KindFilter {
        @Test
        fun `findAll excludes temporary workspaces by default`() = runTest {
            val store = createStore()
            val userProject = project("/tmp/${UUID.randomUUID()}", ProjectKind.USER)
            val tempProject = project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP)
            store.save(userProject, USER)
            store.save(tempProject, USER)

            val listed = store.findAll(userId = USER).toList()

            assertEquals(listOf(userProject.id), listed.map { it.id })
        }

        @Test
        fun `findAll includes temporary workspaces on request`() = runTest {
            val store = createStore()
            val tempProject = project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP)
            store.save(tempProject, USER)

            val listed = store.findAll(userId = USER, includeTemp = true).toList()

            assertEquals(tempProject.id, listed.first { it.id == tempProject.id }.id)
        }

        @Test
        fun `search excludes temporary workspaces`() = runTest {
            val store = createStore()
            val tempProject = project("/tmp/scratch-${UUID.randomUUID()}", ProjectKind.TEMP, name = "scratchy")
            store.save(tempProject, USER)

            assertEquals(emptyList<String>(), store.findAll(search = "scratchy", userId = USER).toList().map { it.id })
        }

        @Test
        fun `findById and findByPath still resolve temporary workspaces`() = runTest {
            val store = createStore()
            val path = "/tmp/${UUID.randomUUID()}"
            val tempProject = project(path, ProjectKind.TEMP)
            store.save(tempProject, USER)

            assertEquals(tempProject.id, store.findById(tempProject.id, USER)?.id)
            assertEquals(tempProject.id, store.findByPath(path, USER)?.id)
        }

        @Test
        fun `another user cannot see the project`() = runTest {
            val store = createStore()
            val tempProject = project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP)
            store.save(tempProject, USER)

            assertNull(store.findById(tempProject.id, OTHER_USER))
            assertEquals(emptyList<String>(), store.findAll(userId = OTHER_USER).toList().map { it.id })
        }
    }

    @Nested
    inner class Maintenance {
        // The class shares one in-memory database, so these tests use their own owners
        // instead of the fixtures the kind-filter tests assert on.
        @Test
        fun `listDistinctUserIds collapses rows of the same owner`() = runTest {
            val store = createStore()
            val owner = "sweep-owner-${UUID.randomUUID()}"
            val other = "sweep-other-${UUID.randomUUID()}"
            store.save(project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP), owner)
            store.save(project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP), owner)
            store.save(project("/tmp/${UUID.randomUUID()}", ProjectKind.USER), other)

            val owners = store.listDistinctUserIds()

            assertEquals(1, owners.count { it == owner })
            assertEquals(1, owners.count { it == other })
        }

        @Test
        fun `saveIfAbsent inserts once and never overwrites the winner`() = runTest {
            val store = createStore()
            val owner = "sweep-owner-${UUID.randomUUID()}"
            val temp = project("/tmp/${UUID.randomUUID()}", ProjectKind.TEMP, name = "first")

            assertEquals(true, store.saveIfAbsent(temp, owner))
            assertEquals(false, store.saveIfAbsent(temp.copy(name = "second"), owner))

            val loaded = store.findById(temp.id, owner)
            assertEquals("first", loaded?.name)
            assertEquals(ProjectKind.TEMP, loaded?.kind)
        }
    }

    companion object {
        private const val USER = "kind-test-user"
        private const val OTHER_USER = "kind-test-other-user"
    }
}
