package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.project.R2dbcAsyncProjectStore
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.*
import java.time.Instant
import java.util.UUID
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Integration tests for the project-less listing scope in [R2dbcAsyncSessionStore]:
 * `tempWorkspaceOnly` keeps only sessions that run in a temporary workspace (kind = temp)
 * or carry no project at all, for both the History list and its tag aggregation.
 */
class R2dbcAsyncSessionStoreTempWorkspaceTest {

    companion object {
        private lateinit var db: R2dbcDatabase

        @BeforeAll
        @JvmStatic
        fun setupDb() = runTest {
            db = R2dbcDatabase.connect(
                url = "r2dbc:h2:mem:///session_temp_ws_test_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
                manager = { TransactionManager(it) }
            )
            DatabaseMigration.defaultTables().execute(db)
        }
    }

    private val store = R2dbcAsyncSessionStore(db)
    private val projectStore = R2dbcAsyncProjectStore(db)

    private suspend fun createProject(kind: ProjectKind): String {
        val id = "p-${UUID.randomUUID()}"
        projectStore.save(
            ProjectInfo(
                id = id,
                name = "project-$id",
                path = "/tmp/${id}_ws",
                kind = kind
            )
        )
        return id
    }

    private suspend fun createSession(sessionId: String, projectId: String?) {
        store.save(
            PersistedSession(
                id = sessionId,
                messages = emptyList(),
                projectId = projectId,
                createdAt = Instant.now(),
                updatedAt = Instant.now()
            )
        )
    }

    @Nested
    inner class `findMetadataByLimit temp workspace scope` {

        @Test
        fun `lists temporary and project-less sessions but not project ones`() = runTest {
            val tempProject = createProject(ProjectKind.TEMP)
            val userProject = createProject(ProjectKind.USER)
            val inTemp = "s-${UUID.randomUUID()}"
            val noProject = "s-${UUID.randomUUID()}"
            val releasedWorkspace = "s-${UUID.randomUUID()}"
            val inProject = "s-${UUID.randomUUID()}"
            createSession(inTemp, tempProject)
            createSession(noProject, null)
            createSession(releasedWorkspace, "p-${UUID.randomUUID()}")
            createSession(inProject, userProject)

            val (scoped, _) = store.findMetadataByLimit(limit = 100, tempWorkspaceOnly = true)
            val ids = scoped.map { it.id }
            assertTrue(inTemp in ids, "a session in a temporary workspace is project-less")
            assertTrue(noProject in ids, "a session without any project is project-less")
            assertTrue(releasedWorkspace in ids, "a session whose workspace row was released stays project-less")
            assertFalse(inProject in ids, "sessions of a registered project must be excluded")

            val (unscoped, _) = store.findMetadataByLimit(limit = 100)
            val unscopedIds = unscoped.map { it.id }
            assertTrue(inTemp in unscopedIds && noProject in unscopedIds && inProject in unscopedIds)
        }

        @Test
        fun `combines with the project filter instead of overriding it`() = runTest {
            val tempProject = createProject(ProjectKind.TEMP)
            val userProject = createProject(ProjectKind.USER)
            val inTemp = "s-${UUID.randomUUID()}"
            val inProject = "s-${UUID.randomUUID()}"
            createSession(inTemp, tempProject)
            createSession(inProject, userProject)

            val (ids, _) = store.findMetadataByLimit(limit = 100, projectId = userProject, tempWorkspaceOnly = true)
            assertTrue(ids.isEmpty(), "a registered project has no temporary sessions")

            val (ownIds, _) = store.findMetadataByLimit(limit = 100, projectId = tempProject, tempWorkspaceOnly = true)
            assertTrue(ownIds.map { it.id } == listOf(inTemp))
        }
    }

    @Nested
    inner class `findAllTags temp workspace scope` {

        @Test
        fun `aggregates tags of project-less sessions only`() = runTest {
            val tempProject = createProject(ProjectKind.TEMP)
            val userProject = createProject(ProjectKind.USER)
            val inTemp = "s-${UUID.randomUUID()}"
            val noProject = "s-${UUID.randomUUID()}"
            val inProject = "s-${UUID.randomUUID()}"
            createSession(inTemp, tempProject)
            createSession(noProject, null)
            createSession(inProject, userProject)

            store.updateTags(inTemp, listOf("scratch"))
            store.updateTags(noProject, listOf("legacy"))
            store.updateTags(inProject, listOf("repo-only"))

            val scoped = store.findAllTags(tempWorkspaceOnly = true)
            assertTrue("scratch" in scoped)
            assertTrue("legacy" in scoped)
            assertFalse("repo-only" in scoped, "tags from registered projects must be excluded")

            assertTrue("repo-only" in store.findAllTags())
        }
    }
}
