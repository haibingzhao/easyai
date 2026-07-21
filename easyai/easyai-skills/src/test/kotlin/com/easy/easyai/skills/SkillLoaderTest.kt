package com.easy.easyai.skills

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

class SkillLoaderTest {

    @Nested
    inner class ParseValidSkillMd {
        @Test
        fun `parses skill with all fields`() {
            val skillContent = """
                ---
                name: code-review
                description: Review code for best practices
                tags:
                  - coding
                  - review
                examples:
                  - "Review a PR"
                  - "Check style guidelines"
                ---

                # Code Review Skill

                When reviewing code, follow these guidelines:
                1. Check for null safety
                2. Verify error handling
                3. Ensure test coverage
            """.trimIndent()

            val tempFile = Path.of(System.getProperty("java.io.tmpdir"), "SKILL.md")
            tempFile.writeText(skillContent)

            val result = SkillLoader.parse(tempFile)

            assertEquals("code-review", result.name)
            assertEquals("Review code for best practices", result.description)
            assertEquals(setOf("coding", "review"), result.tags)
            assertEquals(setOf("Review a PR", "Check style guidelines"), result.examples)
            assertTrue(result.content.contains("# Code Review Skill"))
        }
    }

    @Nested
    inner class ExtractFrontmatter {
        @Test
        fun `returns empty map when no frontmatter`() {
            val content = "# Just markdown\n\nNo frontmatter here."
            val (frontmatter, body) = SkillLoader.extractFrontmatter(content)
            assertTrue(frontmatter.isEmpty())
            assertEquals(content, body)
        }

        @Test
        fun `returns empty map when malformed YAML`() {
            val content = "---\nname: [broken\n---\nbody"
            val (frontmatter, body) = SkillLoader.extractFrontmatter(content)
            assertTrue(frontmatter.isEmpty())
            assertEquals("body", body)
        }

        @Test
        fun `parses name from valid frontmatter`() {
            val content = "---\nname: test-skill\ndescription: A test\n---\nContent"
            val (frontmatter, body) = SkillLoader.extractFrontmatter(content)
            assertEquals("test-skill", frontmatter["name"])
            assertEquals("Content", body)
        }
    }

    @Nested
    inner class Validation {
        @Test
        fun `throws when name missing`(@TempDir tempDir: Path) {
            val skillFile = tempDir.resolve("SKILL.md")
            skillFile.writeText("---\ndescription: no name\n---\nbody")

            assertThrows<IllegalArgumentException> {
                SkillLoader.parse(skillFile)
            }
        }
    }
}