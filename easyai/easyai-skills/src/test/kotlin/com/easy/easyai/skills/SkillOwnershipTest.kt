package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.IOException
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillOwnershipTest {

    private val project = Path.of("/projects/repo")
    private val config = SkillConfig(paths = listOf("/shared"))

    private fun row(
        name: String = "pdf",
        owner: String = "system",
        root: Path? = project,
        enabled: Boolean = true
    ): SkillCatalogEntry {
        val install = root?.resolve(".easyai/skills/group/folder") ?: Path.of("/shared/folder")
        return SkillCatalogEntry(
            id = "$owner:$name:$root",
            name = name,
            checksum = "a".repeat(64),
            installPath = SkillPaths.canonicalize(install),
            userId = owner,
            enabled = enabled,
            projectHash = SkillScopeResolver.projectHashOf(root),
            indexProjectPath = root?.let { SkillPaths.canonicalize(it) }
        )
    }

    private fun catalog(vararg rows: SkillCatalogEntry): AsyncSkillCatalogStore {
        val store = mockk<AsyncSkillCatalogStore>()
        coEvery { store.listByUser(any()) } answers {
            val owner = firstArg<String>()
            rows.filter { it.userId == owner }
        }
        coEvery { store.listByName(any(), any()) } answers {
            val name = firstArg<String>()
            val owner = secondArg<String>()
            rows.filter { it.name == name && it.userId == owner }
        }
        return store
    }

    @Nested
    inner class PerIdentityLoad {

        @Test
        fun `unrelated user rows do not suppress system fallback for another name`() = runTest {
            val shared = row()
            val store = catalog(row("other", "alice"), shared)

            assertEquals(
                SkillLoadPermission.Allowed(shared.installPath),
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            )
            coVerify(exactly = 0) { store.listByUser(any()) }
        }

        @Test
        fun `same name in another user project does not suppress system row here`() = runTest {
            val shared = row()
            val store = catalog(row(owner = "alice", root = project.resolve("module")), shared)
            assertEquals(
                SkillLoadPermission.Allowed(shared.installPath),
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            )
        }

        @Test
        fun `disabled user PROJECT shadows system PROJECT and GLOBAL`() = runTest {
            val store = catalog(row(owner = "alice", enabled = false), row(), row(root = null))
            assertEquals(
                SkillLoadPermission.Disabled,
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            )
            coVerify(exactly = 0) { store.listByName("pdf", "system") }
        }

        @Test
        fun `disabled system PROJECT wins over enabled requester GLOBAL`() = runTest {
            val store = catalog(row(owner = "alice", root = null), row(enabled = false))
            assertEquals(
                SkillLoadPermission.Disabled,
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            )
        }

        @Test
        fun `enabled requester PROJECT wins over disabled system PROJECT`() = runTest {
            val own = row(owner = "alice")
            val store = catalog(own, row(enabled = false))
            assertEquals(
                SkillLoadPermission.Allowed(own.installPath),
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            )
        }

        @Test
        fun `disabled requester GLOBAL shadows system GLOBAL`() = runTest {
            val store = catalog(row(owner = "alice", root = null, enabled = false), row(root = null))
            assertEquals(SkillLoadPermission.Disabled, SkillOwnership.checkLoad(store, "pdf", "alice", null, config))
        }

        @Test
        fun `parent child and prefix projects never supply a load row`() = runTest {
            for (otherRoot in listOf(project.parent, project.resolve("module"), Path.of("/projects/repo-other"))) {
                val store = catalog(row(owner = "alice", root = otherRoot), row(root = otherRoot))
                assertEquals(
                    SkillLoadPermission.NotInstalled,
                    SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
                )
            }
        }

        @Test
        fun `GLOBAL is a fallback only when the exact project has no row`() = runTest {
            val global = row(root = null)
            val store = catalog(row(owner = "alice", root = project.parent, enabled = false), global)
            assertEquals(
                SkillLoadPermission.Allowed(global.installPath),
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            )
        }

        @Test
        fun `unknown location fails closed before system fallback`() = runTest {
            val unknown = row(owner = "alice").copy(installPath = "/unknown/folder")
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByName("pdf", any()) } returns listOf(unknown, row(owner = "bob"))
            assertFailsWith<IllegalArgumentException> {
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            }
            coVerify(exactly = 0) { store.listByName("pdf", "system") }
        }

        @Test
        fun `inconsistent requester identity rejects load and management without fallback`() = runTest {
            val local = row(owner = "alice", enabled = false)
            val invalidRows = listOf(
                local.copy(projectHash = SkillCatalogEntry.GLOBAL_HASH, indexProjectPath = null),
                local.copy(indexProjectPath = null),
                local.copy(indexProjectPath = project.resolve("other").toString()),
                row(owner = "alice", root = null, enabled = false).copy(
                    projectHash = local.projectHash, indexProjectPath = local.indexProjectPath
                )
            )
            for (invalid in invalidRows) {
                val store = catalog(invalid, row(), row(root = null), row(owner = "alice", root = null))
                for (requestedProject in listOf(project, null)) {
                    assertFailsWith<IllegalArgumentException> {
                        SkillOwnership.checkLoad(store, "pdf", "alice", requestedProject, config)
                    }
                    assertFailsWith<IllegalArgumentException> {
                        SkillOwnership.resolveRow(store, "pdf", "alice", requestedProject, config)
                    }
                }
                coVerify(exactly = 0) { store.listByName("pdf", "system") }
            }
        }

        @Test
        fun `invalid system PROJECT identity cannot expose enabled requester GLOBAL`() = runTest {
            val invalid = row(enabled = false).copy(indexProjectPath = null)
            val store = catalog(invalid, row(owner = "alice", root = null), row(root = null))
            assertFailsWith<IllegalArgumentException> {
                SkillOwnership.checkLoad(store, "pdf", "alice", project, config)
            }
        }

        @Test
        fun `rows returned for an unexpected owner cannot authorize a load`() = runTest {
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByName("pdf", any()) } returns listOf(row(owner = "bob"))
            assertEquals(SkillLoadPermission.NotInstalled, SkillOwnership.checkLoad(store, "pdf", "alice", project, config))
        }

        @Test
        fun `explicit owner resolution does not switch to system for management`() = runTest {
            val store = catalog(row())
            assertEquals(null, SkillOwnership.resolveRow(store, "pdf", "alice", project, config))
            coVerify(exactly = 0) { store.listByName("pdf", "system") }
        }
    }

    @Nested
    inner class ReadFailures {

        @Test
        fun `load catalog errors propagate without global fallback`() = runTest {
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByName("pdf", "alice") } throws IOException("read failed")
            assertFailsWith<IOException> { SkillOwnership.checkLoad(store, "pdf", "alice", project, config) }
            coVerify(exactly = 0) { store.listByName("pdf", "system") }
        }

        @Test
        fun `system fallback read failures propagate`() = runTest {
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByName("pdf", "alice") } returns emptyList()
            coEvery { store.listByName("pdf", "system") } throws IOException("read failed")
            assertFailsWith<IOException> { SkillOwnership.checkLoad(store, "pdf", "alice", project, config) }
        }

        @Test
        fun `load cancellation propagates instead of granting access`() = runTest {
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByName("pdf", "alice") } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { SkillOwnership.checkLoad(store, "pdf", "alice", project, config) }
        }

    }
}
