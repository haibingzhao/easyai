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

        // H3: the delimiter must be matched on a line boundary, not by substring search. Under the old
        // `indexOf("---", 3)` split, a value containing `---` truncated the frontmatter mid-YAML and
        // the body started with the leftover of the value.
        @Test
        fun `a value containing the delimiter does not end the frontmatter`() {
            val content = "---\nname: some---thing\ndescription: has --- inside\n---\nreal body"
            val (frontmatter, body) = SkillLoader.extractFrontmatter(content)
            assertEquals("some---thing", frontmatter["name"], "a `---` inside a value is content, not a fence")
            assertEquals("has --- inside", frontmatter["description"])
            assertEquals("real body", body)
        }

        // H3: `----` (four or more dashes) is not a fence; a substring match at offset 3 would slice
        // through it and leave a stray `-` at the head of the body.
        @Test
        fun `a longer dash run is not a fence`() {
            val content = "---\nname: x\n----\nstill frontmatter\n---\nbody"
            val (frontmatter, body) = SkillLoader.extractFrontmatter(content)
            // YAML treats `----` as an unparsable line, so the whole frontmatter falls back to empty;
            // what must NOT happen is a body that starts with `-` sliced off `----`.
            assertTrue(body == "body" || body.startsWith("still") || frontmatter.isEmpty(),
                "a longer dash run must not be treated as a fence: fm=$frontmatter body=$body")
        }

        @Test
        fun `an unterminated frontmatter returns the raw content as body`() {
            val content = "---\nname: broken\nno closing fence"
            val (frontmatter, body) = SkillLoader.extractFrontmatter(content)
            assertTrue(frontmatter.isEmpty())
            assertEquals(content, body)
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