package com.easy.easyai.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillDiscoveryTest {

    private val discovery = DefaultSkillDiscovery()

    @Nested
    inner class ScanDirectory {
        @Test
        fun `finds SKILL md files recursively`(@TempDir tempDir: Path) {
            val subDir = tempDir.resolve("sub").createDirectories()
            val skillFile = subDir.resolve("SKILL.md")
            skillFile.writeText("---\nname: test-skill\ndescription: A test skill\n---\nContent")

            val results = discovery.scanDirectory(tempDir)

            assertEquals(1, results.size)
            assertEquals("test-skill", results[0].name)
        }

        @Test
        fun `returns empty for non-existent directory`() {
            val results = discovery.scanDirectory(Path.of("/nonexistent/path"))
            assertTrue(results.isEmpty())
        }

        @Test
        fun `skips files with invalid yaml frontmatter`(@TempDir tempDir: Path) {
            val skillFile = tempDir.resolve("SKILL.md")
            skillFile.writeText("---\nbroken: [yaml\n---\nbody")

            val results = discovery.scanDirectory(tempDir)

            assertTrue(results.isEmpty())
        }

        @Test
        fun `finds multiple skills in different subdirectories`(@TempDir tempDir: Path) {
            val dir1 = tempDir.resolve("skill-a").createDirectories()
            val dir2 = tempDir.resolve("skill-b").createDirectories()
            dir1.resolve("SKILL.md").writeText("---\nname: skill-a\ndescription: First skill\n---\nA")
            dir2.resolve("SKILL.md").writeText("---\nname: skill-b\ndescription: Second skill\n---\nB")

            val results = discovery.scanDirectory(tempDir)

            assertEquals(2, results.size)
            val names = results.map { it.name }.toSet()
            assertTrue(names.containsAll(setOf("skill-a", "skill-b")))
        }
    }

    @Nested
    inner class DiscoverFromHome {
        @Test
        fun `discovers skills from valid home subdirs`(@TempDir homeDir: Path) {
            val skillsDir = homeDir.resolve(".agents/skills").createDirectories()
            val skillDir = skillsDir.resolve("test").createDirectories()
            skillDir.resolve("SKILL.md").writeText("---\nname: home-skill\ndescription: From home dir\n---\nContent")

            val results = discovery.discoverFromHome(homeDir, listOf(".agents/skills"))

            assertEquals(1, results.size)
            assertEquals("home-skill", results[0].name)
        }

        @Test
        fun `skips non-existent home subdirs`(@TempDir homeDir: Path) {
            val results = discovery.discoverFromHome(homeDir, listOf(".agents/skills"))
            assertTrue(results.isEmpty())
        }
    }

    @Nested
    inner class DiscoverFromPaths {
        @Test
        fun `batch scans multiple directories`(@TempDir tempDir: Path) {
            val dir1 = tempDir.resolve("path1").createDirectories()
            val dir2 = tempDir.resolve("path2").createDirectories()
            dir1.resolve("SKILL.md").writeText("---\nname: path-skill-1\ndescription: First\n---\nA")
            dir2.resolve("SKILL.md").writeText("---\nname: path-skill-2\ndescription: Second\n---\nB")

            val results = discovery.discoverFromPaths(listOf(dir1, dir2))

            assertEquals(2, results.size)
        }
    }
}