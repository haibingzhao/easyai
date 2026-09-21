package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [SkillCatalogService] — the single door through which the management surface changes a
 * skill's lifecycle.
 *
 * The property under test is the one the plan insists on: the catalog row and the retrieval index may
 * never be updated separately, so a toggle must touch both, and a partial failure must be reported
 * instead of letting "disabled" become a lie that `skill_search` and the prompt still tell. And since
 * V6 the row is addressed by primary key after [SkillOwnership.resolveRow] picked the granularity,
 * so a toggle can never reach a same-named row of another project.
 */
class SkillCatalogServiceTest {

    @TempDir
    lateinit var tempDir: Path

    private val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
    private val indexer = mockk<SkillIndexer>(relaxed = true)
    private val skillStore = mockk<SkillStore>(relaxed = true)
    private val promptSource = mockk<SkillPromptSource>(relaxed = true)
    private val config = SkillConfig()

    private lateinit var realHome: String
    private val home: Path get() = tempDir.resolve("home")
    private val project: Path get() = tempDir.resolve("repo")

    @BeforeEach
    fun setUp() {
        // Granularity is derived from the install path against the *real* home directory, so a test
        // that wants a GLOBAL row has to move home into the temporary tree first.
        realHome = System.getProperty("user.home")
        Files.createDirectories(home)
        System.setProperty("user.home", home.toString())
        coEvery { indexer.indexOne(any(), any(), any(), any()) } returns true
        coEvery { indexer.removeOne(any(), any()) } returns true
        // C1: the catalog row is flipped before the index is asked to catch up; a relaxed mock
        // would return `false` here and the toggle would be reported as Rejected.
        coEvery { catalog.setEnabled(any(), any()) } returns true
    }

    @AfterEach
    fun restoreHome() {
        System.setProperty("user.home", realHome)
    }

    private fun service(
        catalogStore: AsyncSkillCatalogStore? = catalog,
        store: SkillStore? = skillStore,
        prompt: SkillPromptSource? = promptSource
    ) = SkillCatalogService(catalogStore, indexer, store, config, prompt)

    /** A row whose install path decides its granularity. */
    private fun row(
        name: String = "pdf",
        userId: String = "alice",
        scope: SkillScope = SkillScope.GLOBAL,
        source: String = SkillCatalogEntry.SOURCE_LOCAL,
        version: String = "1.0.0",
        origin: String? = null,
        enabled: Boolean = true,
        at: Path? = null
    ) = SkillCatalogEntry(
        id = "row-$userId-$name-${scope.name.lowercase()}",
        name = name,
        source = source,
        version = version,
        checksum = "a".repeat(64),
        enabled = enabled,
        installPath = (at ?: (if (scope == SkillScope.GLOBAL) home else project))
            .resolve(".easyai/skills/$name").toString(),
        origin = origin,
        userId = userId
    )

    private val owner: SkillOwnerContext get() = SkillOwnerContext("alice", project)

    @Nested
    inner class `the toggle keeps table and index in step` {

        @Test
        fun `disabling removes the document but keeps the row`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(row())

            val result = service().setEnabled("pdf", owner, enabled = false)

            assertIs<SkillToggleResult.Applied>(result)
            assertFalse(result.enabled)
            assertTrue(result.indexSynced)
            coVerify(exactly = 1) {
                indexer.removeOne(match { it.name == "pdf" && it.userId == "alice" }, removeCatalogRow = false)
            }
            coVerify(exactly = 0) { catalog.delete(any()) }
        }

        @Test
        fun `enabling indexes from disk so edits made while disabled surface`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(row(enabled = false))

            val result = service().setEnabled("pdf", owner, enabled = true)

            assertIs<SkillToggleResult.Applied>(result)
            coVerify(exactly = 1) {
                indexer.indexOne(
                    match { it.enabled && it.name == "pdf" },
                    SkillScope.GLOBAL,
                    any(),
                    await = true
                )
            }
            coVerify(exactly = 0) { indexer.removeOne(any(), any()) }
        }

        @Test
        fun `a project row is removed from the project slice it lives in`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(row(scope = SkillScope.PROJECT))

            service().setEnabled("pdf", owner, enabled = false)

            coVerify(exactly = 1) {
                indexer.removeOne(match { it.installPath == project.resolve(".easyai/skills/pdf").toString() }, removeCatalogRow = false)
            }
        }

        @Test
        fun `a request from one project never toggles the same-named row of another`() = runTest {
            val other = tempDir.resolve("other-repo")
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(
                row(scope = SkillScope.PROJECT, at = other),
            )

            val result = service().setEnabled("pdf", owner, enabled = false)

            assertIs<SkillToggleResult.Rejected>(result, "only the other project's row exists; this request sees nothing")
            coVerify(exactly = 0) { indexer.removeOne(any(), any()) }
            coVerify(exactly = 0) { catalog.setEnabled(any(), any()) }
        }

        @Test
        fun `when both granularities exist the nearest wins`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(
                row(scope = SkillScope.GLOBAL),
                row(scope = SkillScope.PROJECT),
            )

            service().setEnabled("pdf", owner, enabled = false)

            coVerify(exactly = 1) {
                indexer.removeOne(match { it.scope() == SkillScope.PROJECT }, removeCatalogRow = false)
            }
        }

        @Test
        fun `a row this user does not own cannot be toggled`() = runTest {
            coEvery { catalog.listByName("ghost", "alice") } returns emptyList()

            val result = service().setEnabled("ghost", owner, enabled = false)

            assertIs<SkillToggleResult.Rejected>(result)
            coVerify(exactly = 0) { indexer.removeOne(any(), any()) }
            coVerify(exactly = 0) { indexer.indexOne(any(), any(), any(), any()) }
        }

        @Test
        fun `without a catalog layer the toggle is refused, not silently accepted`() = runTest {
            val result = service(catalogStore = null).setEnabled("pdf", owner, enabled = false)

            assertIs<SkillToggleResult.Rejected>(result)
            coVerify(exactly = 0) { indexer.removeOne(any(), any()) }
        }

        @Test
        fun `a toggle still works with no index backend, and says the index did not move`() = runTest {
            val target = row()
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(target)
            coEvery { catalog.setEnabled(target.id, false) } returns true

            val result = service(store = null).setEnabled("pdf", owner, enabled = false)

            assertIs<SkillToggleResult.Applied>(result)
            assertFalse(result.indexSynced, "the row changed, the index does not exist")
            coVerify(exactly = 1) { catalog.setEnabled(target.id, false) }
        }

        @Test
        fun `a half-applied toggle is reported as half applied`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(row())
            coEvery { indexer.removeOne(any(), any()) } returns false

            val result = service().setEnabled("pdf", owner, enabled = false)

            assertIs<SkillToggleResult.Applied>(result)
            assertFalse(result.indexSynced, "search must hide it meanwhile, but reconciliation owes a retry")
        }

        // C1: before the fix, `indexOne` returning false (SKILL.md missing on disk) left the DB row at
        // `enabled=false` while the API returned Applied(enabled=true, indexSynced=false). The UI
        // showed the skill as on; `SkillOwnership.checkLoad` read the DB and refused it.
        @Test
        fun `enabling with a missing SKILL_md still flips the row and reports indexSynced=false`() = runTest {
            val target = row(enabled = false)
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(target)
            coEvery { indexer.indexOne(any(), any(), any(), any()) } returns false

            val result = service().setEnabled("pdf", owner, enabled = true)

            assertIs<SkillToggleResult.Applied>(result)
            assertTrue(result.enabled)
            assertFalse(result.indexSynced, "no SKILL.md means nothing to embed, but the row is the source of truth")
            coVerify(exactly = 1) { catalog.setEnabled(target.id, true) }
            coVerify(exactly = 1) { promptSource.refreshVisibility() }
        }

        // C1 corollary: if the row write itself fails, the index must not be touched and the caller
        // must see Rejected — otherwise the two sides drift in the opposite direction.
        @Test
        fun `a failed catalog row write leaves the index untouched and reports Rejected`() = runTest {
            val target = row()
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(target)
            coEvery { catalog.setEnabled(target.id, false) } returns false

            val result = service().setEnabled("pdf", owner, enabled = false)

            assertIs<SkillToggleResult.Rejected>(result)
            coVerify(exactly = 0) { indexer.removeOne(any(), any()) }
            coVerify(exactly = 0) { indexer.indexOne(any(), any(), any(), any()) }
            coVerify(exactly = 0) { promptSource.refreshVisibility() }
        }

        @Test
        fun `the prompt view learns about the toggle in the same call`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(row())

            service().setEnabled("pdf", owner, enabled = false)

            coVerify(exactly = 1) { promptSource.refreshVisibility() }
        }

        @Test
        fun `an anonymous request acts on the default owner rows`() = runTest {
            coEvery { catalog.listByName("pdf", SkillCatalogEntry.DEFAULT_USER_ID) } returns
                listOf(row(userId = SkillCatalogEntry.DEFAULT_USER_ID))

            val result = service().setEnabled("pdf", SkillOwnerContext(null, null), enabled = false)

            assertIs<SkillToggleResult.Applied>(result)
            coVerify(exactly = 1) {
                indexer.removeOne(match { it.userId == SkillCatalogEntry.DEFAULT_USER_ID }, removeCatalogRow = false)
            }
        }
    }

    @Nested
    inner class `the catalog view` {

        @Test
        fun `rows carry their granularity and whether the files are still there`() = runTest {
            val present = row(name = "pdf").let {
                Path.of(it.installPath).resolve("SKILL.md").also { file ->
                    file.parent.createDirectories()
                    file.writeText("---\nname: pdf\n---\n\nbody\n")
                }
                it
            }
            val absent = row(name = "xlsx")
            coEvery { catalog.listByUser("alice") } returns listOf(present, absent)

            val views = service().list(owner)

            assertEquals(listOf("pdf", "xlsx"), views.map { it.entry.name })
            assertTrue(views.first().installedOnDisk)
            assertEquals(SkillScope.GLOBAL, views.first().scope)
            assertFalse(views.last().installedOnDisk, "reconciliation will delist this row; the UI can warn")
        }

        @Test
        fun `one row can be read without listing the whole catalogue`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns listOf(row(scope = SkillScope.PROJECT))

            val view = service().find("pdf", owner)

            assertEquals(SkillScope.PROJECT, view?.scope)
            assertEquals(project, view?.projectPath)
        }

        @Test
        fun `a name owned by somebody else is simply not there`() = runTest {
            coEvery { catalog.listByName("pdf", "alice") } returns emptyList()

            assertNull(service().find("pdf", owner))
        }

        @Test
        fun `without a catalog layer the surface is empty rather than broken`() = runTest {
            assertEquals(emptyList<SkillCatalogView>(), service(catalogStore = null).list(owner))
            assertNull(service(catalogStore = null).find("pdf", owner))
        }
    }
}

/** Test-only view of the granularity a row's install path implies. */
private fun SkillCatalogEntry.scope(): SkillScope = SkillScopeResolver.resolve(this, SkillConfig()).first
