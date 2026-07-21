package com.easy.easyai.core.prompt

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InstructionsLoaderTest {

    @Nested
    inner class `load project instructions` {

        @Test
        fun `returns empty list when projectPath is null`() {
            val result = InstructionsLoader.load(null)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `returns empty list when no instruction files exist`() {
            val dir = createTempDirectory("no-instructions")
            val result = InstructionsLoader.load(dir)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `loads AGENTS_md when it exists`() {
            val dir = createTempDirectory("agents-md")
            val content = "# My Project\nDo not use println."
            dir.resolve("AGENTS.md").writeText(content)

            val result = InstructionsLoader.load(dir)
            assertEquals(1, result.size)
            assertEquals("AGENTS.md", result[0].name)
            assertEquals(content, result[0].content)
            assertEquals(InstructionSource.PROJECT, result[0].source)
            assertNotNull(result[0].location)
        }

        @Test
        fun `loads CLAUDE_md when AGENTS_md does not exist`() {
            val dir = createTempDirectory("claude-md")
            val content = "# Claude instructions"
            dir.resolve("CLAUDE.md").writeText(content)

            val result = InstructionsLoader.load(dir)
            assertEquals(1, result.size)
            assertEquals("CLAUDE.md", result[0].name)
        }

        @Test
        fun `loads CONTEXT_md when neither AGENTS nor CLAUDE exist`() {
            val dir = createTempDirectory("context-md")
            dir.resolve("CONTEXT.md").writeText("context content")

            val result = InstructionsLoader.load(dir)
            assertEquals(1, result.size)
            assertEquals("CONTEXT.md", result[0].name)
        }

        @Test
        fun `first match wins - AGENTS_md takes priority over CLAUDE_md`() {
            val dir = createTempDirectory("priority")
            dir.resolve("AGENTS.md").writeText("agents content")
            dir.resolve("CLAUDE.md").writeText("claude content")

            val result = InstructionsLoader.load(dir)
            assertEquals(1, result.size)
            assertEquals("AGENTS.md", result[0].name)
            assertEquals("agents content", result[0].content)
        }

        @Test
        fun `truncates files exceeding max size`() {
            val dir = createTempDirectory("large-file")
            val largeContent = "x".repeat(9 * 1024)  // > 8KB
            dir.resolve("AGENTS.md").writeText(largeContent)

            val result = InstructionsLoader.load(dir)
            assertEquals(1, result.size)
            assertTrue(result[0].content.endsWith("[...truncated...]"))
            assertTrue(result[0].content.length < largeContent.length)
        }
    }

    @Nested
    inner class `formatForPrompt` {

        @Test
        fun `returns null for empty list`() {
            assertNull(InstructionsLoader.formatForPrompt(emptyList()))
        }

        @Test
        fun `formats single instruction with header and filename`() {
            val dir = createTempDirectory("format")
            val file = dir.resolve("AGENTS.md")
            file.writeText("Do not use println.")

            val instructions = listOf(
                InstructionInfo(
                    name = "AGENTS.md",
                    content = "Do not use println.",
                    source = InstructionSource.PROJECT,
                    location = file
                )
            )
            val result = InstructionsLoader.formatForPrompt(instructions)
            assertNotNull(result)
            assertTrue(result.contains("## Project Instructions"))
            assertTrue(result.contains("### From: AGENTS.md"))
            assertTrue(result.contains("Do not use println."))
        }
    }

    @Nested
    inner class `resolveForFileRead` {

        @Test
        fun `finds AGENTS_md in subdirectories`() {
            val root = createTempDirectory("root")
            root.resolve("AGENTS.md").writeText("root instructions")
            val sub = root.resolve("sub")
            sub.toFile().mkdirs()
            sub.resolve("AGENTS.md").writeText("sub instructions")

            val deep = sub.resolve("deep")
            deep.toFile().mkdirs()

            val alreadyLoaded = setOf(root.resolve("AGENTS.md").toAbsolutePath())
            val result = InstructionsLoader.resolveForFileRead(
                fileDir = deep,
                projectRoot = root,
                alreadyLoaded = alreadyLoaded
            )

            assertEquals(1, result.size)
            assertEquals("sub instructions", result[0].content)
            assertEquals(InstructionSource.SUBDIR, result[0].source)
        }

        @Test
        fun `deduplicates already loaded files`() {
            val root = createTempDirectory("dedup")
            val agentsFile = root.resolve("AGENTS.md")
            agentsFile.writeText("root instructions")

            val sub = root.resolve("sub")
            sub.toFile().mkdirs()

            // Already loaded the root AGENTS.md
            val alreadyLoaded = setOf(agentsFile.toAbsolutePath())
            val result = InstructionsLoader.resolveForFileRead(
                fileDir = sub,
                projectRoot = root,
                alreadyLoaded = alreadyLoaded
            )

            assertTrue(result.isEmpty())
        }

        @Test
        fun `returns empty when no instruction files in subdirectory chain`() {
            val root = createTempDirectory("empty-chain")
            val sub = root.resolve("sub")
            sub.toFile().mkdirs()

            val result = InstructionsLoader.resolveForFileRead(
                fileDir = sub,
                projectRoot = root,
                alreadyLoaded = emptySet()
            )

            assertTrue(result.isEmpty())
        }
    }
}
