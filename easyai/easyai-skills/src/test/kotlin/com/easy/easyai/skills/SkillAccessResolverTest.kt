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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillAccessResolverTest {

    private val project = Path.of("/projects/repo")
    private val config = SkillConfig(enabled = false, paths = listOf("/shared"))
    private val registry = DefaultSkillRegistry(DefaultSkillDiscovery(), config)

    private fun skill(name: String, root: Path? = project, folder: String = name): SkillInfo {
        val install = root?.resolve(".easyai/skills/$folder") ?: Path.of("/shared/$folder")
        return SkillInfo(name, name, install.resolve("SKILL.md"), "body")
    }

    private fun row(skill: SkillInfo, owner: String = "system", enabled: Boolean = true) = SkillCatalogEntry(
        id = "$owner:${skill.location}",
        name = skill.name,
        checksum = "a".repeat(64),
        installPath = SkillPaths.canonicalize(skill.location.parent),
        userId = owner,
        enabled = enabled,
        projectHash = SkillScopeResolver.projectHashOf(SkillScopeResolver.resolve(skill, config).second),
        indexProjectPath = SkillScopeResolver.resolve(skill, config).second?.let { SkillPaths.canonicalize(it) }
    )

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
    inner class Candidates {

        @Test
        fun `PROJECT and GLOBAL of one name stay separate even with mixed owners`() = runTest {
            val local = skill("pdf", folder = "group/local-folder")
            val global = skill("pdf", null, "different-folder")
            registry.register(local)
            registry.register(global)
            val localRow = row(local)
            val globalRow = row(global, "alice")
            val store = catalog(localRow, globalRow)

            val result = SkillAccessResolver(registry, store, config).listScopedSkills("alice", project)

            assertEquals(listOf(ScopedSkill(local, localRow), ScopedSkill(global, globalRow)), result)
            coVerify(exactly = 1) { store.listByUser("alice") }
            coVerify(exactly = 1) { store.listByUser("system") }
            coVerify(exactly = 0) { store.listAll() }
        }

        @Test
        fun `requester override is per identity and unrelated system items remain`() = runTest {
            val own = skill("owned")
            val shared = skill("system-only")
            val global = skill("owned", null)
            listOf(own, shared, global).forEach { registry.register(it) }
            val ownRow = row(own, "alice", enabled = false)
            val sharedRow = row(shared)
            val globalRow = row(global)
            val store = catalog(row(own), ownRow, sharedRow, globalRow)

            val result = SkillAccessResolver(registry, store, config).listScopedSkills("alice", project)

            assertEquals(listOf(ownRow, sharedRow, globalRow), result.map { it.catalogEntry })
            assertFalse(result.first().catalogEntry!!.enabled)
            assertEquals(
                SkillLoadPermission.Disabled,
                SkillOwnership.checkLoad(store, "owned", "alice", project, config)
            )
        }

        @Test
        fun `parent child and prefix sibling are excluded despite matching owner`() = runTest {
            val local = skill("local")
            val parent = skill("parent", project.parent)
            val child = skill("child", project.resolve("module"))
            val sibling = skill("sibling", Path.of("/projects/repo-other"))
            val global = skill("global", null)
            val skills = listOf(local, parent, child, sibling, global)
            skills.forEach { registry.register(it) }
            val store = catalog(*skills.map { row(it, "alice") }.toTypedArray())
            val resolver = SkillAccessResolver(registry, store, config)

            assertEquals(listOf(local, global), resolver.listScopedSkills("alice", project.resolve("other/..")).map { it.skill })
            assertEquals(listOf(global), resolver.listScopedSkills("alice", null).map { it.skill })
        }

        @Test
        fun `anonymous blank and system requests never query another owner`() = runTest {
            val global = skill("global", null)
            registry.register(global)
            val store = catalog(row(global), row(global, "alice"))
            val resolver = SkillAccessResolver(registry, store, config)

            for (user in listOf(null, " ", "system")) {
                assertEquals("system", resolver.listScopedSkills(user, null).single().catalogEntry?.userId)
            }
            coVerify(exactly = 3) { store.listByUser("system") }
            coVerify(exactly = 0) { store.listByUser("alice") }
        }
    }

    @Nested
    inner class InstallAuthorization {

        @Test
        fun `a registry winner at another users install path is not exposed`() = runTest {
            val owned = skill("pdf", folder = "alice-folder")
            val other = skill("pdf", folder = "bob-folder")
            registry.register(owned)
            registry.register(other)
            val store = catalog(row(owned, "alice"), row(other, "bob"))

            assertTrue(SkillAccessResolver(registry, store, config).listScopedSkills("alice", project).isEmpty())
            assertEquals(listOf(other), registry.all(), "access resolution must not replace the registry root winner")
        }

        @Test
        fun `a mismatched requester row blocks fallback to a matching system install`() = runTest {
            val owned = skill("pdf", folder = "alice-folder")
            val shared = skill("pdf", folder = "system-folder")
            registry.register(shared)
            val store = catalog(row(owned, "alice"), row(shared))

            assertTrue(SkillAccessResolver(registry, store, config).listScopedSkills("alice", project).isEmpty())
        }

        @Test
        fun `catalog install path comparison is exact but lexically normalized`() = runTest {
            val local = skill("pdf", folder = "folder")
            registry.register(local)
            val wrongPrefix = row(local, "alice").copy(installPath = "$project/.easyai/skills/folder-other")
            assertTrue(SkillAccessResolver(registry, catalog(wrongPrefix), config).listScopedSkills("alice", project).isEmpty())
            val equivalent = row(local, "alice").copy(installPath = "$project/.easyai/skills/group/../folder")
            assertEquals(
                listOf(ScopedSkill(local, equivalent)),
                SkillAccessResolver(registry, catalog(equivalent), config).listScopedSkills("alice", project)
            )
        }

        @Test
        fun `rows for another owner returned by a store are still rejected`() = runTest {
            val local = skill("pdf")
            registry.register(local)
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByUser(any()) } returns listOf(row(local, "bob"))

            assertTrue(SkillAccessResolver(registry, store, config).listScopedSkills("alice", project).isEmpty())
        }

        @Test
        fun `unclaimed skills and unknown catalog locations do not authorize a registry candidate`() = runTest {
            val global = skill("global", null)
            registry.register(global)
            val unknown = row(global).copy(installPath = "/unknown/folder")
            assertFailsWith<IllegalArgumentException> {
                SkillAccessResolver(registry, catalog(unknown), config).listScopedSkills("alice", null)
            }
            assertTrue(SkillAccessResolver(registry, catalog(), config).listScopedSkills("alice", null).isEmpty())
        }
    }

    @Nested
    inner class WithoutCatalog {

        @Test
        fun `only explicitly shared sources are exposed without inferring project ownership`() = runTest {
            val global = skill("global", null)
            val local = skill("local")
            val other = skill("other", Path.of("/users/bob/project"))
            val home = SkillInfo(
                "home", location = Path.of(System.getProperty("user.home"), ".agents/skills/folder/SKILL.md"), content = "body"
            )
            listOf(global, local, other, home).forEach { registry.register(it) }
            val resolver = SkillAccessResolver(registry, null, config)

            for (requestedProject in listOf(project, Path.of("/users/bob/project"), null)) {
                val result = resolver.listScopedSkills("alice", requestedProject)
                assertEquals(listOf(global, home), result.map { it.skill })
                result.forEach { assertNull(it.catalogEntry) }
            }
        }

        @Test
        fun `config paths inside a project root do not make it shared without catalog`() = runTest {
            val local = skill("local")
            val configured = config.copy(paths = listOf(project.resolve(".easyai/skills").toString()))
            val underTest = DefaultSkillRegistry(DefaultSkillDiscovery(), configured)
            underTest.register(local)

            assertTrue(SkillAccessResolver(underTest, null, configured).listScopedSkills("alice", project).isEmpty())
        }
    }

    @Nested
    inner class FailClosed {

        @Test
        fun `inconsistent identity fails the request instead of exposing system or GLOBAL fallbacks`() = runTest {
            val local = skill("pdf")
            val global = skill("pdf", null)
            registry.register(local)
            registry.register(global)
            val invalid = row(local, "alice", enabled = false).copy(projectHash = "", indexProjectPath = null)
            val store = catalog(invalid, row(local), row(global), row(global, "alice"))
            assertFailsWith<IllegalArgumentException> {
                SkillAccessResolver(registry, store, config).listScopedSkills("alice", project)
            }
            val prompt = SkillPromptSource(registry, store, true, false, config = config)
            assertFailsWith<IllegalArgumentException> {
                prompt.skillsForPrompt("alice", project, listOf("pdf"))
            }
            assertFailsWith<IllegalArgumentException> { prompt.effectiveNames("alice", project) }
            coVerify(exactly = 0) { store.listByUser("system") }
        }

        @Test
        fun `catalog read errors propagate instead of exposing shared candidates`() = runTest {
            registry.register(skill("global", null))
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByUser("alice") } throws IOException("unavailable")
            assertFailsWith<IOException> { SkillAccessResolver(registry, store, config).listScopedSkills("alice", null) }
        }

        @Test
        fun `catalog cancellation propagates`() = runTest {
            val store = mockk<AsyncSkillCatalogStore>()
            coEvery { store.listByUser("alice") } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> {
                SkillAccessResolver(registry, store, config).listScopedSkills("alice", project)
            }
        }
    }
}
