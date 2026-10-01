package com.easy.easyai.skills

import com.easy.easyai.core.skill.SkillCatalogEntry
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.deleteIfExists
import kotlin.io.path.writeText

/**
 * Tests for [DefaultSkillRegistry], the in-memory snapshot keyed by (owner, name).
 *
 * `get(owner, name)` falls through to the shared layer while `visibleFor(owner)` is the shadowing
 * view the model sees. Neither is an authorization decision — that is the catalog gate's job — so
 * these tests only pin the addressing and the scan lifecycle.
 */
class SkillRegistryTest {

    private val discovery = DefaultSkillDiscovery()

    /** Scanning off, root outside the tree: registration is entirely manual in these tests. */
    private fun manualRegistry(): SkillRegistry =
        DefaultSkillRegistry(discovery, SkillConfig(enabled = false, rootDir = "/.easyai-fixture-skills"))

    private fun skill(name: String, owner: String = "alice", description: String = name, content: String = "content") =
        SkillInfo(
            name = name,
            description = description,
            location = SkillPaths.installDir(SkillPaths.ownerRoot(SkillConfig(rootDir = "/.easyai-fixture-skills"), owner), name)
                .resolve(SkillPaths.SKILL_FILE_NAME),
            content = content,
        )

    @Nested
    inner class RegisterAndLookup {

        @Test
        fun `register and get returns skill`() {
            val registry = manualRegistry()
            val expected = skill("test", description = "Test skill")

            registry.register("alice", expected)

            assertEquals(expected, registry.get("alice", "test"))
        }

        @Test
        fun `the same name under two owners is two entries and each owner hits its own`() {
            val registry = manualRegistry()
            registry.register("alice", skill("pdf", owner = "alice", description = "Alice"))
            registry.register("bob", skill("pdf", owner = "bob", description = "Bob"))

            assertEquals("Alice", registry.get("alice", "pdf")?.description)
            assertEquals("Bob", registry.get("bob", "pdf")?.description)
            assertNull(registry.get("carol", "pdf"), "a stranger gets neither tenant's skill")
        }

        @Test
        fun `get falls through to the shared layer`() {
            val registry = manualRegistry()
            registry.register(SkillCatalogEntry.DEFAULT_USER_ID, skill("pdf", description = "Shared"))

            assertEquals("Shared", registry.get("alice", "pdf")?.description)
            assertNull(registry.get("alice", "missing"))
        }

        @Test
        fun `own skill shadows the shared namesake in the model view only`() {
            val registry = manualRegistry()
            val shared = skill("pdf", owner = SkillCatalogEntry.DEFAULT_USER_ID, description = "Shared")
            val own = skill("pdf", owner = "alice", description = "Own")
            registry.register(SkillCatalogEntry.DEFAULT_USER_ID, shared)
            registry.register("alice", own)

            assertEquals(listOf("Own"), registry.visibleFor("alice").map { it.description })
            assertEquals(listOf("Shared"), registry.visibleFor(SkillCatalogEntry.DEFAULT_USER_ID).map { it.description })
            assertEquals("Shared", registry.get("bob", "pdf")?.description, "shadowing is a view rule, not a delete")
        }

        @Test
        fun `re-registering the same key replaces the entry`() {
            val registry = manualRegistry()
            registry.register("alice", skill("pdf", description = "First"))

            registry.register("alice", skill("pdf", description = "Second"))

            assertEquals(listOf("Second"), registry.visibleFor("alice").map { it.description })
        }

        @Test
        fun `remove drops only the addressed owner`() {
            val registry = manualRegistry()
            registry.register("alice", skill("pdf"))
            registry.register(SkillCatalogEntry.DEFAULT_USER_ID, skill("pdf", owner = SkillCatalogEntry.DEFAULT_USER_ID, description = "Shared"))

            assertEquals("pdf", registry.remove("alice", "pdf")?.name)
            assertEquals("Shared", registry.get("alice", "pdf")?.description, "removing the shadow exposes the shared layer")
            assertNull(registry.remove("alice", "pdf"))
        }
    }

    /**
     * Re-scanning is what makes a SKILL.md written during a session loadable without a restart, so the
     * delta it reports is the answer an agent acts on: it must say which (owner, name) appeared, which
     * vanished, and nothing at all when the disk did not move.
     */
    @Nested
    inner class ReScan {

        private fun registry(rootDir: Path) = DefaultSkillRegistry(discovery, SkillConfig(rootDir = rootDir.toString()))

        private fun writeSkill(ownerRoot: Path, name: String, body: String = "Content"): Path {
            val skillDir = ownerRoot.resolve(name).createDirectories()
            skillDir.resolve(SkillPaths.SKILL_FILE_NAME)
                .writeText("---\nname: $name\ndescription: $name does things\n---\n$body")
            return skillDir
        }

        @Test
        fun `the first read scans the disk lazily`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            writeSkill(rootDir.resolve("alice"), "pdf")

            assertEquals(listOf("pdf"), underScan.visibleFor("alice").map { it.name })
        }

        @Test
        fun `a manual register does not spend the lazy scan`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            writeSkill(rootDir.resolve("alice"), "installed")
            underScan.register("bob", skill("draft", owner = "bob"))

            assertNotNull(underScan.get("alice", "installed"), "publishing one skill must not hide the rest of the disk")
            assertEquals("draft", underScan.get("bob", "draft")?.name)
        }

        @Test
        fun `a skill written after startup becomes loadable on the next re-scan`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            assertTrue(underScan.visibleFor("alice").isEmpty(), "the owner root did not exist at construction time")

            writeSkill(rootDir.resolve("alice"), "pdf")
            val delta = underScan.rescan(emptySet())

            assertEquals(listOf(SkillKey("alice", "pdf")), delta.added)
            assertEquals(1, delta.total)
            assertNotNull(underScan.get("alice", "pdf"))
        }

        @Test
        fun `a skill whose file is gone stops being loadable`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            val skillDir = writeSkill(rootDir.resolve("alice"), "pdf")
            underScan.rescan(emptySet())

            skillDir.resolve(SkillPaths.SKILL_FILE_NAME).deleteIfExists()
            val delta = underScan.rescan(emptySet())

            assertEquals(listOf(SkillKey("alice", "pdf")), delta.removed)
            assertEquals(emptyList<SkillKey>(), delta.added)
            assertNull(underScan.get("alice", "pdf"))
            assertEquals(0, delta.total)
        }

        @Test
        fun `an unchanged re-scan reports nothing and keeps the snapshot`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            writeSkill(rootDir.resolve("alice"), "pdf")
            underScan.rescan(emptySet())

            val second = underScan.rescan(emptySet())

            assertEquals(emptyList<SkillKey>(), second.added, "a steady state must not look like churn")
            assertEquals(emptyList<SkillKey>(), second.removed)
            assertEquals(emptyList<SkillKey>(), second.updatedKeys)
            assertEquals(1, second.total)
        }

        @Test
        fun `an edited skill is reported as updated, not as churn`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            val skillDir = writeSkill(rootDir.resolve("alice"), "pdf")
            underScan.rescan(emptySet())

            skillDir.resolve(SkillPaths.SKILL_FILE_NAME)
                .writeText("---\nname: pdf\ndescription: pdf does things\n---\nRevised content")
            val delta = underScan.rescan(emptySet())

            assertEquals(listOf(SkillKey("alice", "pdf")), delta.updatedKeys)
            assertEquals(emptyList<SkillKey>(), delta.added)
            assertEquals(emptyList<SkillKey>(), delta.removed)
            assertTrue(underScan.get("alice", "pdf")!!.content.contains("Revised"))
        }

        @Test
        fun `a pinned root answers under the catalog owner id, not the directory name`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            val hostId = "tenant/7"
            val ownerRoot = rootDir.resolve(SkillPaths.safeSegment(hostId))
            writeSkill(ownerRoot, "pdf")
            underScan.pinOwners(mapOf(SkillPaths.canonicalize(ownerRoot) to hostId))

            val delta = underScan.rescan(emptySet())

            assertEquals(listOf(SkillKey(hostId, "pdf")), delta.added)
            assertEquals("pdf does things", underScan.get(hostId, "pdf")?.description)
        }

        @Test
        fun `a root outside the skill tree is never adopted`(@TempDir rootDir: Path, @TempDir elsewhere: Path) {
            val underScan = registry(rootDir)
            writeSkill(elsewhere.resolve("alice"), "pdf")

            val delta = underScan.rescan(setOf(elsewhere.resolve("alice")))

            assertEquals(emptyList<SkillKey>(), delta.added, "rescan takes owner roots, and those must sit under rootDir")
        }

        @Test
        fun `a re-scan that names fewer roots still keeps the other owners`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            writeSkill(rootDir.resolve("alice"), "pdf")
            writeSkill(rootDir.resolve("bob"), "pdf")
            assertEquals(2, underScan.rescan(setOf(rootDir.resolve("alice"), rootDir.resolve("bob"))).total)

            // A refresh triggered by one user must never prune another user's skill: known roots are
            // re-read on every pass, so nothing is "missing" just because it was not asked for.
            val delta = underScan.rescan(setOf(rootDir.resolve("alice")))

            assertEquals(emptyList<SkillKey>(), delta.removed)
            assertNotNull(underScan.get("bob", "pdf"), "bob's snapshot survived a scan that only mentioned alice")
        }

        @Test
        fun `an unparseable skill is skipped without failing the pass`(@TempDir rootDir: Path) {
            val underScan = registry(rootDir)
            writeSkill(rootDir.resolve("alice"), "good")
            val broken = rootDir.resolve("alice").resolve("broken").createDirectories()
            broken.resolve(SkillPaths.SKILL_FILE_NAME).writeText("---\ndescription: no name at all\n---\nContent")

            val delta = underScan.rescan(emptySet())

            assertEquals(listOf(SkillKey("alice", "good")), delta.added)
            assertEquals(1, delta.total)
        }
    }
}
