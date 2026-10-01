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
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillAccessResolverTest {

    private val config = SkillConfig(rootDir = "/.easyai-skill-test-root")
    private val registry = DefaultSkillRegistry(DefaultSkillDiscovery(), config)

    private fun skill(name: String, owner: String = "alice", folder: String = name): SkillInfo {
        val dir = Path.of(config.rootDir, SkillPaths.safeSegment(owner), folder)
        return SkillInfo(name, name, dir.resolve(SkillPaths.SKILL_FILE_NAME), "body")
    }

    private fun row(
        skill: SkillInfo,
        owner: String = "alice",
        enabled: Boolean = true
    ): SkillCatalogEntry {
        val installDir = skill.location.parent!!
        return SkillCatalogEntry(
            id = "$owner:${skill.name}", name = skill.name, checksum = "a".repeat(64),
            rootPath = SkillPaths.canonicalize(installDir.parent!!),
            installPath = SkillPaths.canonicalize(installDir),
            userId = owner, enabled = enabled
        )
    }

    private fun catalog(vararg rows: SkillCatalogEntry): AsyncSkillCatalogStore {
        val store = mockk<AsyncSkillCatalogStore>()
        coEvery { store.listByUser(any()) } answers {
            val owner = firstArg<String>()
            rows.filter { it.userId == owner }
        }
        return store
    }

    @Nested
    inner class NameBinding {

        @Test
        fun `own skill shadows the shared namesake for the viewer`() = runTest {
            val own = skill("pdf")
            val shared = skill("pdf", owner = "system")
            registry.register("alice", own)
            registry.register("system", shared)
            val ownRow = row(own, "alice")
            val store = catalog(ownRow, row(shared, SkillCatalogEntry.DEFAULT_USER_ID))

            val result = SkillAccessResolver(registry, store).listScopedSkills("alice")

            assertEquals(listOf(ownRow), result.map { it.catalogEntry })
            assertTrue(result.single().shared.not())
        }

        @Test
        fun `unrelated shared and personal names both stay visible`() = runTest {
            val own = skill("owned")
            val shared = skill("system-only", owner = "system")
            registry.register("alice", own)
            registry.register("system", shared)
            val ownRow = row(own, "alice", enabled = false)
            val sharedRow = row(shared, SkillCatalogEntry.DEFAULT_USER_ID)
            val store = catalog(ownRow, sharedRow)

            val result = SkillAccessResolver(registry, store).listScopedSkills("alice")

            assertEquals(listOf(ownRow, sharedRow), result.map { it.catalogEntry })
            assertTrue(result.first { it.catalogEntry === sharedRow }.shared)
            // Binding keeps disabled rows; enablement filtering belongs to the model view.
            assertTrue(result.first { it.catalogEntry === ownRow }.catalogEntry!!.enabled.not())
        }

        @Test
        fun `registry candidate without any winning row stays hidden`() = runTest {
            val orphan = skill("orphan")
            registry.register("alice", orphan)
            val result = SkillAccessResolver(registry, catalog()).listScopedSkills("alice")
            assertTrue(result.isEmpty())
        }

        @Test
        fun `blank null and system requests resolve to the shared owner only`() = runTest {
            val shared = skill("shared", owner = "system")
            registry.register("system", shared)
            val sharedRow = row(shared, SkillCatalogEntry.DEFAULT_USER_ID)
            val store = catalog(sharedRow, row(skill("shared"), "alice"))
            val resolver = SkillAccessResolver(registry, store)

            for (user in listOf(null, " ", "system")) {
                assertEquals(SkillCatalogEntry.DEFAULT_USER_ID, resolver.listScopedSkills(user).single().catalogEntry?.userId)
            }
            coVerify(exactly = 3) { store.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) }
            coVerify(exactly = 0) { store.listByUser("alice") }
        }
    }

    @Nested
    inner class InstallAuthorization {

        @Test
        fun `a candidate whose location no row of the winner pins is not exposed`() = runTest {
            val misplaced = skill("pdf", owner = "bob", folder = "bob-folder")
            registry.register("alice", misplaced)
            // Alice's row pins her own root. A directory inside bob's tree is nothing alice's row
            // authorizes, so the scanned candidate stays hidden instead of leaking another owner's files.
            val store = catalog(
                row(misplaced, "alice").copy(
                    installPath = "${config.rootDir}/alice/pdf",
                    rootPath = "${config.rootDir}/alice"
                )
            )

            assertTrue(SkillAccessResolver(registry, store).listScopedSkills("alice").isEmpty())
        }

        @Test
        fun `a mismatched own row blocks fallback to the matching system install`() = runTest {
            val shared = skill("pdf", owner = "system")
            val drifted = skill("pdf", owner = "alice", folder = "pdf-old")
            registry.register("alice", drifted)
            registry.register("system", shared)
            // Alice's stale row pins a path the registry no longer carries: binding fails closed
            // because the winner of the name is alice, and alice's install path does not match.
            val store = catalog(row(drifted, "alice").copy(installPath = "/.easyai-skill-test-root/alice/removed"), row(shared, SkillCatalogEntry.DEFAULT_USER_ID))

            assertTrue(SkillAccessResolver(registry, store).listScopedSkills("alice").isEmpty())
        }

        @Test
        fun `catalog install path comparison is exact but lexically normalized`() = runTest {
            val pdf = skill("pdf")
            registry.register("alice", pdf)
            val wrongPrefix = row(pdf).copy(installPath = "${config.rootDir}/alice/pdf-other")
            assertTrue(
                SkillAccessResolver(registry, catalog(wrongPrefix)).listScopedSkills("alice").isEmpty()
            )
            val equivalent = row(pdf).copy(installPath = "${config.rootDir}/alice/group/../pdf")
            assertEquals(
                listOf(ScopedSkill(pdf, equivalent)),
                SkillAccessResolver(registry, catalog(equivalent)).listScopedSkills("alice")
            )
        }

        @Test
        fun `rows for another owner returned by a store are still rejected`() = runTest {
            val pdf = skill("pdf")
            registry.register("alice", pdf)
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByUser(any()) } returns listOf(row(pdf, "bob"))

            assertTrue(SkillAccessResolver(registry, store).listScopedSkills("alice").isEmpty())
        }
    }

    @Nested
    inner class WithoutCatalog {

        @Test
        fun `registry snapshot is exposed unbound`() = runTest {
            val own = skill("own")
            val shared = skill("shared", owner = "system")
            registry.register("alice", own)
            registry.register("system", shared)
            val resolver = SkillAccessResolver(registry, null)

            val result = resolver.listScopedSkills("alice")
            assertEquals(listOf("own", "shared"), result.map { it.skill.name })
            result.forEach { assertNull(it.catalogEntry) }
            assertTrue(result.none { it.shared })
        }
    }

    @Nested
    inner class FailClosed {

        @Test
        fun `catalog read errors propagate instead of exposing unbound candidates`() = runTest {
            registry.register("system", skill("shared", owner = "system"))
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByUser("alice") } throws IOException("unavailable")
            assertFailsWith<IOException> { SkillAccessResolver(registry, store).listScopedSkills("alice") }
        }

        @Test
        fun `cancellation propagates`() = runTest {
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByUser("alice") } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                SkillAccessResolver(registry, store).listScopedSkills("alice")
            }
        }
    }
}
