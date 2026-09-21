package com.easy.easyai.skills

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for [SkillPromptSource] — the decision of which skills the system prompt advertises.
 *
 * The four combinations that matter are the ones the rollout contract is about: flag off, flag on
 * with a working index, flag on with a missing index bean, and full injection with disabled rows.
 * The third one is the regression guard: a downed RAG service must never leave the agent with no
 * skill discovery at all.
 */
class SkillPromptSourceTest {

    private val registry = mockk<SkillRegistry>()

    private fun skill(name: String, description: String? = "Does $name things") = SkillInfo(
        name = name,
        description = description,
        location = Path.of("/home/dev/.easyai/skills/$name/SKILL.md"),
        content = "# $name"
    )

    private fun row(name: String, userId: String, enabled: Boolean = true) = SkillCatalogEntry(
        id = "$userId/$name",
        name = name,
        enabled = enabled,
        checksum = "a".repeat(64),
        installPath = "/home/dev/.easyai/skills/$name",
        userId = userId
    )

    private fun source(
        catalog: AsyncSkillCatalogStore? = null,
        inject: Boolean = true,
        ragEnabled: Boolean = false,
        ragDiscoveryReady: Boolean = ragEnabled,
        registry: SkillRegistry? = this.registry
    ) = SkillPromptSource(
        registry = registry,
        catalog = catalog,
        injectIntoSystemPrompt = inject,
        ragEnabled = ragEnabled,
        ragDiscoveryReady = ragDiscoveryReady
    )

    private fun names(list: List<Map<String, Any?>>): List<String> = list.map { it["name"] as String }

    @Nested
    inner class `which view the prompt gets` {

        @Test
        fun `with the flag off the whole catalogue rides along as before`() {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"), skill("git-flow"))

            val prompt = source()

            assertTrue(prompt.fullInjectionActive)
            assertEquals(listOf("pdf-report", "git-flow"), names(prompt.skillsForPrompt("alice")))
        }

        @Test
        fun `a skill with nothing to say is never advertised`() {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"), skill("scaffold", description = "  "))

            assertEquals(listOf("pdf-report"), names(source().skillsForPrompt("alice")))
        }

        @Test
        fun `on-demand discovery empties the prompt view`() {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"))

            val prompt = source(ragEnabled = true, ragDiscoveryReady = true)

            assertFalse(prompt.fullInjectionActive)
            assertEquals(emptyList(), names(prompt.skillsForPrompt("alice")))
        }

        @Test
        fun `the flag alone is not enough, a missing store keeps the full list`() {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"))

            val prompt = source(ragEnabled = true, ragDiscoveryReady = false)

            assertTrue(
                prompt.fullInjectionActive,
                "skill_search would not resolve without a SkillStore bean, so suppression would blind the agent"
            )
            assertEquals(listOf("pdf-report"), names(prompt.skillsForPrompt("alice")))
        }

        @Test
        fun `injection switched off outright is respected whatever RAG says`() {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"))

            val prompt = source(inject = false, ragEnabled = false)

            assertFalse(prompt.fullInjectionActive)
            assertEquals(emptyList(), names(prompt.skillsForPrompt("alice")))
        }

        @Test
        fun `the view is the requesting project's view, not the union`() {
            val global = skill("git-flow")
            val inProject = SkillInfo(
                name = "pdf",
                description = "Does pdf things",
                location = Path.of("/work/repo/.easyai/skills/pdf/SKILL.md"),
                content = "# pdf"
            )
            val project = Path.of("/work/repo")
            every { registry.visibleFor(null) } returns listOf(global)
            every { registry.visibleFor(project) } returns listOf(inProject, global)

            val prompt = source()

            assertEquals(listOf("git-flow"), names(prompt.skillsForPrompt("alice")))
            assertEquals(
                listOf("pdf", "git-flow"),
                names(prompt.skillsForPrompt("alice", project)),
                "a session inside a project sees that project's skills too — the same view load_skill will honor"
            )
        }

        @Test
        fun `no registry at all is an empty view, not a crash`() {
            val prompt = source(registry = null)

            assertFalse(prompt.fullInjectionActive)
            assertEquals(emptyList(), names(prompt.skillsForPrompt("alice")))
        }
    }

    @Nested
    inner class `the disabled view` {

        private val catalog = mockk<AsyncSkillCatalogStore>()

        @Test
        fun `nothing is hidden before the first refresh`() = runTest {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"))
            val prompt = source(catalog = catalog)

            assertEquals(listOf("pdf-report"), names(prompt.skillsForPrompt("alice")))
        }

        @Test
        fun `a disabled row is hidden for its owner and for nobody else`() = runTest {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"), skill("git-flow"))
            coEvery { catalog.listDistinctUserIds() } returns listOf("alice")
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf-report", "alice", enabled = false))
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns emptyList()
            val prompt = source(catalog = catalog)

            assertTrue(prompt.refreshVisibility())

            assertEquals(listOf("git-flow"), names(prompt.skillsForPrompt("alice")))
            assertEquals(
                listOf("pdf-report", "git-flow"),
                names(prompt.skillsForPrompt("bob")),
                "another user's switch-off must not hide a skill here"
            )
        }

        @Test
        fun `a disable hides one install directory, not every skill of that name`() = runTest {
            val pdfInA = SkillInfo(
                name = "pdf",
                description = "Builds pdf in A",
                location = Path.of("/work/repo-a/.easyai/skills/pdf/SKILL.md"),
                content = "# a"
            )
            val pdfInB = SkillInfo(
                name = "pdf",
                description = "Builds pdf in B",
                location = Path.of("/work/repo-b/.easyai/skills/pdf/SKILL.md"),
                content = "# b"
            )
            every { registry.visibleFor(null) } returns listOf(pdfInA, pdfInB)
            coEvery { catalog.listDistinctUserIds() } returns listOf("alice")
            coEvery { catalog.listByUser("alice") } returns listOf(
                SkillCatalogEntry(
                    id = "row-a",
                    name = "pdf",
                    checksum = "a".repeat(64),
                    enabled = false,
                    installPath = "/work/repo-a/.easyai/skills/pdf",
                    userId = "alice"
                )
            )
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns emptyList()
            val prompt = source(catalog = catalog)

            prompt.refreshVisibility()

            val view = prompt.skillsForPrompt("alice")
            assertEquals(1, view.size, "switching off project A's pdf must not silence project B's copy")
            assertEquals("Builds pdf in B", view.first()["description"])
        }

        @Test
        fun `an anonymous request inherits the default owner`() = runTest {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"))
            coEvery { catalog.listDistinctUserIds() } returns listOf(SkillCatalogEntry.DEFAULT_USER_ID)
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns
                listOf(row("pdf-report", SkillCatalogEntry.DEFAULT_USER_ID, enabled = false))

            assertEquals(emptyList(), names(source(catalog = catalog).apply { refreshVisibility() }.skillsForPrompt(null)))
        }

        @Test
        fun `a blank user id is the anonymous case too`() = runTest {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"))
            coEvery { catalog.listDistinctUserIds() } returns listOf("alice")
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf-report", "alice", enabled = false))
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns emptyList()
            val prompt = source(catalog = catalog)
            prompt.refreshVisibility()

            assertEquals(
                listOf("pdf-report"),
                names(prompt.skillsForPrompt("   ")),
                "a blank identity has no claim on alice's switches"
            )
        }

        @Test
        fun `a failed refresh keeps hiding what was already hidden`() = runTest {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"), skill("git-flow"))
            coEvery { catalog.listDistinctUserIds() } returns listOf("alice")
            coEvery { catalog.listByUser("alice") } returns listOf(row("pdf-report", "alice", enabled = false))
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns emptyList()
            val prompt = source(catalog = catalog)
            prompt.refreshVisibility()

            coEvery { catalog.listDistinctUserIds() } throws IllegalStateException("database is down")

            assertFalse(prompt.refreshVisibility(), "a failed pass must report that it changed nothing")
            assertEquals(listOf("git-flow"), names(prompt.skillsForPrompt("alice")))
        }

        @Test
        fun `without a table there is nothing to refresh`() = runTest {
            every { registry.visibleFor(null) } returns listOf(skill("pdf-report"))

            assertFalse(source().refreshVisibility())
            assertEquals(listOf("pdf-report"), names(source().skillsForPrompt("alice")))
        }
    }
}
