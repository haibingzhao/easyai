package com.easy.easyai.web.service

import com.easy.easyai.core.model.ProjectInfo
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.permission.PermissionRuleStore
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.snapshot.SnapshotService
import io.mockk.coEvery
import io.mockk.coJustRun
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/**
 * Covers the per-session temporary workspace lifecycle: deterministic idempotent allocation,
 * path-segment safety, and the cascade release that keeps scratch directories, permission
 * rules and shadow git repos from outliving their session.
 */
class DefaultWorkspaceServiceTest {

    private val projectStore: AsyncProjectStore = mockk(relaxed = true)
    private val sessionStore: AsyncSessionStore = mockk(relaxed = true)
    private val permissionRuleStore: PermissionRuleStore = mockk(relaxed = true)
    private val snapshotService: SnapshotService = mockk(relaxed = true)

    @TempDir
    lateinit var dataDir: Path

    private fun createService(): DefaultWorkspaceService = DefaultWorkspaceService(
        projectStore = projectStore,
        sessionStore = sessionStore,
        permissionRuleStore = permissionRuleStore,
        snapshotService = snapshotService,
        dataDir = dataDir.toString()
    )

    private fun tempProject(sessionId: String, path: Path) = ProjectInfo(
        id = "tmp-$sessionId",
        name = "temp",
        path = path.toString(),
        kind = ProjectKind.TEMP
    )

    @Nested
    inner class Allocation {
        @Test
        fun `first call creates the directory and a temp project row`() = runTest {
            coEvery { projectStore.findById(any(), any()) } returns null
            coEvery { projectStore.saveIfAbsent(any(), any()) } returns true
            val service = createService()

            val workspace = service.resolveOrCreate(SESSION, USER)

            assertEquals("tmp-$SESSION", workspace.projectId)
            assertEquals(ProjectKind.TEMP, workspace.kind)
            assertEquals(dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION), workspace.path)
            assertTrue(Files.isDirectory(workspace.path))

            val saved = slot<ProjectInfo>()
            coVerify { projectStore.saveIfAbsent(capture(saved), USER) }
            assertEquals(ProjectKind.TEMP, saved.captured.kind)
        }

        @Test
        fun `repeat call reuses the existing row without saving`() = runTest {
            val dir = dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION)
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returns tempProject(SESSION, dir)
            val service = createService()

            val workspace = service.resolveOrCreate(SESSION, USER)

            assertEquals("tmp-$SESSION", workspace.projectId)
            assertEquals(dir, workspace.path)
            assertTrue(Files.isDirectory(dir), "missing directory is re-created")
            coVerify(exactly = 0) { projectStore.saveIfAbsent(any(), any()) }
        }

        @Test
        fun `concurrent allocation converges on the persisted row`() = runTest {
            val dir = dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION)
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returnsMany
                listOf(null, tempProject(SESSION, dir))
            coEvery { projectStore.saveIfAbsent(any(), any()) } returns false
            val service = createService()

            val workspace = service.resolveOrCreate(SESSION, USER)

            assertEquals("tmp-$SESSION", workspace.projectId)
            assertEquals(dir, workspace.path)
            assertEquals(ProjectKind.TEMP, workspace.kind)
        }

        @Test
        fun `a save failure that is not a conflict surfaces instead of running on a phantom row`() = runTest {
            coEvery { projectStore.findById(any(), any()) } returns null
            coEvery { projectStore.saveIfAbsent(any(), any()) } throws IllegalStateException("connection reset")
            val service = createService()

            assertThrows<IllegalStateException> { service.resolveOrCreate(SESSION, USER) }
        }

        @Test
        fun `a lost insert whose row is invisible to this user is rejected`() = runTest {
            coEvery { projectStore.findById(any(), any()) } returns null
            coEvery { projectStore.saveIfAbsent(any(), any()) } returns false
            val service = createService()

            assertThrows<IllegalStateException> { service.resolveOrCreate(SESSION, USER) }
        }

        @Test
        fun `ids that are not plain path segments are rejected`() = runTest {
            coEvery { projectStore.findById(any(), any()) } returns null
            val service = createService()

            assertThrows<IllegalArgumentException> { service.resolveOrCreate("../escape", USER) }
            assertThrows<IllegalArgumentException> { service.resolveOrCreate("a".repeat(65), USER) }
            assertThrows<IllegalArgumentException> { service.resolveOrCreate(SESSION, "bad/user") }
            coVerify(exactly = 0) { projectStore.saveIfAbsent(any(), any()) }
        }
    }

    @Nested
    inner class Sweep {
        private val dir: Path get() = dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION)

        private fun stubSweep(project: ProjectInfo) {
            coEvery { projectStore.listDistinctUserIds() } returns listOf(USER)
            every { projectStore.findAll(limit = any(), search = any(), userId = USER, includeTemp = true) } returns
                flowOf(project)
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returns tempProject(SESSION, dir)
        }

        @Test
        fun `releases a workspace whose session is gone`() = runTest {
            Files.createDirectories(dir)
            stubSweep(tempProject(SESSION, dir).copy(createdAt = Instant.now().minusSeconds(3600)))
            coEvery { sessionStore.findIdsByProjectId("tmp-$SESSION", USER) } returns emptyList()
            val service = createService()

            assertEquals(1, service.sweepOrphanWorkspaces())

            assertFalse(Files.exists(dir))
            coVerify { projectStore.delete("tmp-$SESSION", USER) }
        }

        @Test
        fun `keeps a workspace that is still inside the grace window`() = runTest {
            Files.createDirectories(dir)
            stubSweep(tempProject(SESSION, dir))
            coEvery { sessionStore.findIdsByProjectId("tmp-$SESSION", USER) } returns emptyList()
            val service = createService()

            assertEquals(0, service.sweepOrphanWorkspaces())

            assertTrue(Files.exists(dir), "an in-flight first turn must not lose its workspace")
            coVerify(exactly = 0) { projectStore.delete(any(), any()) }
        }

        @Test
        fun `keeps a workspace whose session still exists`() = runTest {
            Files.createDirectories(dir)
            stubSweep(tempProject(SESSION, dir).copy(createdAt = Instant.now().minusSeconds(3600)))
            coEvery { sessionStore.findIdsByProjectId("tmp-$SESSION", USER) } returns listOf(SESSION)
            val service = createService()

            assertEquals(0, service.sweepOrphanWorkspaces())

            assertTrue(Files.exists(dir))
            coVerify(exactly = 0) { projectStore.delete(any(), any()) }
        }

        @Test
        fun `keeps a workspace a fork still refers to after its root session was deleted`() = runTest {
            Files.createDirectories(dir)
            stubSweep(tempProject(SESSION, dir).copy(createdAt = Instant.now().minusSeconds(3600)))
            coEvery { sessionStore.findIdsByProjectId("tmp-$SESSION", USER) } returns listOf("fork-of-$SESSION")
            val service = createService()

            assertEquals(0, service.sweepOrphanWorkspaces())

            assertTrue(Files.exists(dir))
            coVerify(exactly = 0) { projectStore.delete(any(), any()) }
        }

        @Test
        fun `ignores user projects`() = runTest {
            val real = ProjectInfo(
                id = "p-1", name = "repo", path = dataDir.resolve("user-repo").toString(),
                kind = ProjectKind.USER, createdAt = Instant.now().minusSeconds(3600)
            )
            coEvery { projectStore.listDistinctUserIds() } returns listOf(USER)
            every { projectStore.findAll(limit = any(), search = any(), userId = USER, includeTemp = true) } returns
                flowOf(real)
            coEvery { sessionStore.findIdsByProjectId(any(), any()) } returns emptyList()
            val service = createService()

            assertEquals(0, service.sweepOrphanWorkspaces())

            coVerify(exactly = 0) { projectStore.delete(any(), any()) }
        }

        @Test
        fun `is a no-op when session existence cannot be checked`() = runTest {
            val service = DefaultWorkspaceService(
                projectStore = projectStore,
                permissionRuleStore = permissionRuleStore,
                snapshotService = snapshotService,
                dataDir = dataDir.toString()
            )

            assertEquals(0, service.sweepOrphanWorkspaces())

            coVerify(exactly = 0) { projectStore.listDistinctUserIds() }
        }

        @Test
        fun `one failing release does not abort the rest of the sweep`() = runTest {
            val otherSession = "9c1e0f3a-5b7d-4e2f-8a6c-0d3b5e7f1a92"
            val otherDir = dataDir.resolve(".temp-workspaces").resolve(USER).resolve(otherSession)
            Files.createDirectories(dir)
            Files.createDirectories(otherDir)
            val stale = Instant.now().minusSeconds(3600)
            coEvery { projectStore.listDistinctUserIds() } returns listOf(USER)
            every { projectStore.findAll(limit = any(), search = any(), userId = USER, includeTemp = true) } returns
                flowOf(
                    tempProject(SESSION, dir).copy(createdAt = stale),
                    tempProject(otherSession, otherDir).copy(createdAt = stale)
                )
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returns tempProject(SESSION, dir)
            coEvery { projectStore.findById("tmp-$otherSession", USER) } returns
                tempProject(otherSession, otherDir)
            coEvery { sessionStore.findById(any(), USER) } returns null
            coEvery { permissionRuleStore.deleteProjectRules("tmp-$SESSION") } throws
                IllegalStateException("db down")
            val service = createService()

            assertEquals(1, service.sweepOrphanWorkspaces())

            assertTrue(Files.exists(dir), "the failed workspace is left for the next pass")
            assertFalse(Files.exists(otherDir))
        }
    }

    @Nested
    inner class TemporaryPathDetection {
        @Test
        fun `paths under the workspace root are temporary`() {
            val service = createService()
            val workspace = dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION)

            assertTrue(service.isTemporaryPath(workspace.toString()))
            assertTrue(service.isTemporaryPath("$workspace/"), "trailing separator")
        }

        @Test
        fun `paths outside the workspace root are not temporary`() {
            val service = createService()

            assertFalse(service.isTemporaryPath(dataDir.resolve("user-repo").toString()))
            assertFalse(service.isTemporaryPath(dataDir.resolve(".temp-workspaces").toString()), "the root itself")
            assertFalse(service.isTemporaryPath(dataDir.resolve(".temp-workspaces-x/y").toString()))
        }

        @Test
        fun `blank and escaping paths are not temporary`() {
            val service = createService()

            assertFalse(service.isTemporaryPath(null))
            assertFalse(service.isTemporaryPath("   "))
            assertFalse(service.isTemporaryPath(dataDir.resolve(".temp-workspaces").resolve(USER).resolve("../../x").toString()))
        }
    }

    @Nested
    inner class Release {
        @Test
        fun `temp workspace drops rules, snapshot repo, files and row`() = runTest {
            val dir = Files.createDirectories(dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION))
            Files.writeString(dir.resolve("notes.txt"), "scratch")
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returns tempProject(SESSION, dir)
            val service = createService()

            service.releaseWorkspace("tmp-$SESSION", USER)

            assertFalse(Files.exists(dir))
            coVerify { permissionRuleStore.deleteProjectRules("tmp-$SESSION") }
            coVerify { snapshotService.removeWorkspace(dir) }
            coVerify { projectStore.delete("tmp-$SESSION", USER) }
        }

        @Test
        fun `user projects are never released as workspaces`() = runTest {
            val real = Files.createDirectories(dataDir.resolve("user-repo"))
            Files.writeString(real.resolve("keep.txt"), "precious")
            coEvery { projectStore.findById("p-1", USER) } returns ProjectInfo(
                id = "p-1", name = "repo", path = real.toString(), kind = ProjectKind.USER
            )
            val service = createService()

            service.releaseWorkspace("p-1", USER)

            assertTrue(Files.exists(real.resolve("keep.txt")))
            coVerify(exactly = 0) { projectStore.delete(any(), any()) }
            coVerify(exactly = 0) { snapshotService.removeWorkspace(any()) }
        }

        @Test
        fun `a sibling session keeps the shared files, the row goes with the workspace`() = runTest {
            val dir = Files.createDirectories(dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION))
            Files.writeString(dir.resolve("notes.txt"), "still needed by the fork")
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returns tempProject(SESSION, dir)
            coEvery { sessionStore.findIdsByProjectId("tmp-$SESSION", USER) } returns listOf("other-session")
            val service = createService()

            service.releaseForSession(SESSION, "tmp-$SESSION", USER)

            assertTrue(Files.exists(dir))
            coVerify(exactly = 0) { projectStore.delete(any(), any()) }
        }

        @Test
        fun `last session gone deletes files and prunes the per-user directory`() = runTest {
            val dir = Files.createDirectories(dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION))
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returns tempProject(SESSION, dir)
            coEvery { sessionStore.findIdsByProjectId("tmp-$SESSION", USER) } returns listOf(SESSION)
            val service = createService()

            service.releaseForSession(SESSION, "tmp-$SESSION", USER)

            assertFalse(Files.exists(dir))
            assertFalse(Files.exists(dataDir.resolve(".temp-workspaces").resolve(USER)), "empty per-user directory pruned")
            assertTrue(Files.exists(dataDir.resolve(".temp-workspaces")))
        }
    }

    @Nested
    inner class SnapshotFailures {
        @Test
        fun `snapshot cleanup failure does not keep the workspace row`() = runTest {
            val dir = Files.createDirectories(dataDir.resolve(".temp-workspaces").resolve(USER).resolve(SESSION))
            coEvery { projectStore.findById("tmp-$SESSION", USER) } returns tempProject(SESSION, dir)
            coEvery { snapshotService.removeWorkspace(any()) } throws RuntimeException("git dir gone")
            coJustRun { permissionRuleStore.deleteProjectRules(any()) }
            val service = createService()

            service.releaseWorkspace("tmp-$SESSION", USER)

            coVerify { projectStore.delete("tmp-$SESSION", USER) }
        }
    }

    companion object {
        private const val SESSION = "5f0d7b7c-2a4e-4d3b-9d3f-1c8e0a6b2d41"
        private const val USER = "4a1b9d2e-7c5f-4b3a-8f1d-2e6c9a0b5d73"
    }
}
