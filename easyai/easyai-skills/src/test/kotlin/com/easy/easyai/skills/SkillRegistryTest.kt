package com.easy.easyai.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

class SkillRegistryTest {

    private val discovery = DefaultSkillDiscovery()

    @Nested
    inner class RegisterAndLookup {
        private lateinit var registry: SkillRegistry

        @BeforeEach
        fun setUp() {
            // Use a config that doesn't auto-discover to have a clean state
            val config = SkillConfig(enabled = false)
            registry = DefaultSkillRegistry(discovery, config)
        }

        @Test
        fun `register and get returns skill`() {
            val skill = SkillInfo(
                name = "test",
                description = "Test skill",
                location = Path.of("/test/SKILL.md"),
                content = "content",
            )
            registry.register(skill)

            val result = registry.get("test", null)
            assertEquals(skill, result)
        }

        @Test
        fun `same name in two projects coexists and each request hits its own`() {
            val config = SkillConfig(
                enabled = false,
                homeSkillDirs = listOf(".easyai/skills"),
            )
            val reg = DefaultSkillRegistry(discovery, config)
            val alpha = SkillInfo("pdf", "Alpha", Path.of("/projects/alpha/.easyai/skills/pdf/SKILL.md"), "a")
            val beta = SkillInfo("pdf", "Beta", Path.of("/projects/beta/.easyai/skills/pdf/SKILL.md"), "b")

            reg.register(alpha)
            reg.register(beta)

            assertEquals("Alpha", reg.get("pdf", Path.of("/projects/alpha"))?.description)
            assertEquals("Beta", reg.get("pdf", Path.of("/projects/beta"))?.description)
            assertNull(reg.get("pdf", null), "a project-granularity skill must not answer a GLOBAL request")
            assertEquals(2, reg.all().size, "the composite key keeps both rows in the snapshot")
        }

        @Test
        fun `all returns all registered skills`() {
            registry.register(SkillInfo("a", "A", Path.of("/a/SKILL.md"), "content"))
            registry.register(SkillInfo("b", "B", Path.of("/b/SKILL.md"), "content"))

            assertEquals(2, registry.all().size)
        }

        @Test
        fun `get returns null for unknown skill`() {
            assertNull(registry.get("nonexistent", null))
        }

        @Test
        fun `dirs tracks skill parent directories`() {
            registry.register(SkillInfo("test", "Test", Path.of("/some/path/SKILL.md"), "content"))
            val dirs = registry.dirs()
            assertTrue(dirs.any { it.toString().contains("/some/path") })
        }
    }

    /**
     * Re-scanning is what makes a SKILL.md written during a session loadable without a restart, so the
     * delta it reports is the answer an agent acts on: it must say what appeared, what vanished, and
     * nothing at all when the disk did not move.
     */
    @Nested
    inner class ReScan {

        /** Isolated sources: one explicit root, and home/ancestor scanning left off unless asked for. */
        private fun registry(root: Path, ancestorDirNames: List<String> = emptyList()) = DefaultSkillRegistry(
            discovery,
            SkillConfig(
                enabled = true,
                paths = listOf(root.toString()),
                homeSkillDirs = ancestorDirNames,
                workDir = root.toString()
            )
        )

        private fun writeSkill(root: Path, name: String): Path {
            val skillDir = root.resolve(name).createDirectories()
            skillDir.resolve("SKILL.md").writeText("---\nname: $name\ndescription: $name does things\n---\nContent")
            return skillDir
        }

        @Test
        fun `a skill written after startup becomes loadable on the next re-scan`(@TempDir tempDir: Path) {
            val underScan = registry(tempDir)
            assertNull(underScan.get("pdf", null), "the directory did not exist at construction time")

            writeSkill(tempDir, "pdf")
            val delta = underScan.rescan(emptySet())

            assertEquals(listOf("pdf"), delta.added)
            assertEquals(1, delta.total)
            assertNotNull(underScan.get("pdf", null))
        }

        @Test
        fun `a skill whose file is gone stops being loadable`(@TempDir tempDir: Path) {
            val underScan = registry(tempDir)
            val skillDir = writeSkill(tempDir, "pdf")
            underScan.rescan(emptySet())

            skillDir.resolve("SKILL.md").deleteIfExists()
            val delta = underScan.rescan(emptySet())

            assertEquals(listOf("pdf"), delta.removed)
            assertEquals(emptyList<String>(), delta.added)
            assertNull(underScan.get("pdf", null))
            assertEquals(0, delta.total)
        }

        @Test
        fun `an unchanged re-scan reports nothing and keeps the snapshot`(@TempDir tempDir: Path) {
            val underScan = registry(tempDir)
            writeSkill(tempDir, "pdf")
            underScan.rescan(emptySet())

            val second = underScan.rescan(emptySet())

            assertEquals(emptyList<String>(), second.added, "a steady state must not look like churn")
            assertEquals(emptyList<String>(), second.removed)
            assertEquals(1, second.total)
            assertNotNull(underScan.get("pdf", null))
        }

        @Test
        fun `the extra root reaches a project the server was not started in`(@TempDir tempDir: Path) {
            val project = tempDir.resolve("work/repo").createDirectories()
            val underScan = registry(tempDir.resolve("server"), ancestorDirNames = listOf("nested-skills"))
            writeSkill(project.resolve("nested-skills"), "pdf")

            assertEquals(emptyList<String>(), underScan.rescan(emptySet()).added, "the request root is not free real estate")
            val delta = underScan.rescan(setOf(project))

            assertEquals(listOf("pdf"), delta.added)
            assertEquals(listOf(SkillKey("pdf", project)), delta.addedKeys)
            assertEquals(
                project.resolve("nested-skills/pdf"),
                underScan.get("pdf", project)?.location?.parent,
                "the discovered directory is what the catalog row and the load gate compare against"
            )
        }

        @Test
        fun `a re-scan that names fewer roots still keeps the other projects`(@TempDir tempDir: Path) {
            val alpha = tempDir.resolve("alpha").createDirectories()
            val beta = tempDir.resolve("beta").createDirectories()
            val underScan = registry(tempDir.resolve("server"), ancestorDirNames = listOf("nested-skills"))
            writeSkill(alpha.resolve("nested-skills"), "pdf")
            writeSkill(beta.resolve("nested-skills"), "pdf")
            underScan.rescan(setOf(alpha, beta))
            assertEquals(2, underScan.all().size)

            // A refresh triggered by one session must never prune the other project's skill:
            // known roots are re-read on every pass, so nothing is "missing" just because it was not asked for.
            val delta = underScan.rescan(setOf(alpha))

            assertEquals(emptyList<String>(), delta.removed)
            assertNotNull(underScan.get("pdf", beta), "beta's snapshot survived a scan that only mentioned alpha")
        }

        @Test
        fun `re-registering the same location is not reported as a name collision`(@TempDir tempDir: Path) {
            val underScan = registry(tempDir)
            writeSkill(tempDir, "pdf")
            underScan.rescan(emptySet())
            val seenDirs = underScan.dirs()

            underScan.rescan(emptySet())

            assertEquals(
                seenDirs, underScan.dirs(),
                "dirs() is a record of every directory ever seen, so a re-scan only adds to it"
            )
            assertTrue(Files.isDirectory(tempDir.resolve("pdf")))
        }
    }
}
