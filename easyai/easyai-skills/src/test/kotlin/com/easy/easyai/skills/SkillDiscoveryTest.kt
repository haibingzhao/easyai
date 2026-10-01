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

    private fun skill(root: Path, dirName: String, name: String = dirName) {
        root.resolve(dirName).createDirectories().resolve("SKILL.md")
            .writeText("---\nname: $name\ndescription: $name skill\n---\nContent")
    }

    @Nested
    inner class OwnerRoot {
        @Test
        fun `discovers every skill directory under one owner root`(@TempDir ownerRoot: Path) {
            skill(ownerRoot, "alpha")
            skill(ownerRoot, "beta")

            assertEquals(setOf("alpha", "beta"), discovery.discoverOwnerRoot(ownerRoot).map { it.name }.toSet())
        }

        @Test
        fun `an absent owner root yields nothing`() {
            assertTrue(discovery.discoverOwnerRoot(Path.of("/nonexistent/owner")).isEmpty())
        }

        @Test
        fun `a root holding no skill directory yields nothing`(@TempDir ownerRoot: Path) {
            ownerRoot.resolve("notes.md").writeText("not a skill")
            ownerRoot.resolve("empty-dir").createDirectories()

            assertTrue(discovery.discoverOwnerRoot(ownerRoot).isEmpty())
        }

        @Test
        fun `one unparsable skill does not hide the others`(@TempDir ownerRoot: Path) {
            ownerRoot.resolve("broken").createDirectories().resolve("SKILL.md")
                .writeText("---\nbroken: [yaml\n---\nbody")
            skill(ownerRoot, "fine")

            assertEquals(listOf("fine"), discovery.discoverOwnerRoot(ownerRoot).map { it.name })
        }
    }

    /**
     * A skill's payload (scripts, references, a vendored checkout) lives below its own directory and
     * is copied verbatim by the package codec — only `{root}/{name}/SKILL.md` is ever a skill.
     */
    @Nested
    inner class ScanShape {
        @Test
        fun `ignores SKILL md files nested below a skill directory`(@TempDir ownerRoot: Path) {
            ownerRoot.resolve("alpha/references").createDirectories().resolve("SKILL.md")
                .writeText("---\nname: nested\ndescription: Payload, not a skill\n---\nContent")

            assertTrue(discovery.discoverOwnerRoot(ownerRoot).isEmpty())
        }

        @Test
        fun `ignores dot directories`(@TempDir ownerRoot: Path) {
            skill(ownerRoot, ".restore-123", "staged")

            assertTrue(discovery.discoverOwnerRoot(ownerRoot).isEmpty(), "staged restores are working state")
        }
    }
}
