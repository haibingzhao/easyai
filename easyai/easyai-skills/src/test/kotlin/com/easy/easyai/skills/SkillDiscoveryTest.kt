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

    /**
     * A re-scan runs per tool call now, so the walk has to be bounded: an unbounded one would follow
     * vendored trees and every nested checkout under a skill root on each call.
     */
    @Nested
    inner class ScanLimits {
        @Test
        fun `finds a skill within the scan depth`(@TempDir tempDir: Path) {
            val deep = tempDir.resolve("a/b/c").createDirectories()
            deep.resolve("SKILL.md").writeText("---\nname: within\ndescription: Inside the bound\n---\nContent")

            assertEquals(listOf("within"), discovery.scanDirectory(tempDir).map { it.name })
        }

        @Test
        fun `stops below the scan depth`(@TempDir tempDir: Path) {
            val tooDeep = tempDir.resolve("a/b/c/d").createDirectories()
            tooDeep.resolve("SKILL.md").writeText("---\nname: beyond\ndescription: Out of reach\n---\nContent")

            assertTrue(discovery.scanDirectory(tempDir).isEmpty(), "a skill that deep is vendored content")
        }

        @Test
        fun `does not walk into vendored or generated trees`(@TempDir tempDir: Path) {
            for (ignored in listOf("node_modules", ".git", "__pycache__")) {
                val nested = tempDir.resolve(ignored).resolve("pkg").createDirectories()
                nested.resolve("SKILL.md").writeText("---\nname: $ignored\ndescription: Not a skill\n---\nContent")
            }

            assertTrue(discovery.scanDirectory(tempDir).isEmpty(), "dependency and cache directories hold no skills")
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