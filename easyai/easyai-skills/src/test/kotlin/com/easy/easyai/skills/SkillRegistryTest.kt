package com.easy.easyai.skills

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.nio.file.Path

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

            val result = registry.get("test")
            assertEquals(skill, result)
        }

        @Test
        fun `duplicate name replaces with warning`() {
            val skill1 = SkillInfo("test", "First", Path.of("/dir1/SKILL.md"), "content1")
            val skill2 = SkillInfo("test", "Second", Path.of("/dir2/SKILL.md"), "content2")

            registry.register(skill1)
            registry.register(skill2)

            val result = registry.get("test")
            assertEquals("Second", result?.description)
        }

        @Test
        fun `all returns all registered skills`() {
            registry.register(SkillInfo("a", "A", Path.of("/a/SKILL.md"), "content"))
            registry.register(SkillInfo("b", "B", Path.of("/b/SKILL.md"), "content"))

            assertEquals(2, registry.all().size)
        }

        @Test
        fun `get returns null for unknown skill`() {
            assertNull(registry.get("nonexistent"))
        }
    }

    @Nested
    inner class FormatSkills {
        private lateinit var registry: SkillRegistry

        @BeforeEach
        fun setUp() {
            val config = SkillConfig(enabled = false)
            registry = DefaultSkillRegistry(discovery, config)
        }

        @Test
        fun `concise format includes name and description`() {
            registry.register(SkillInfo("review", "Review code", Path.of("/r/SKILL.md"), "content"))

            val result = registry.format(verbose = false)

            assertTrue(result.contains("## Available Skills"))
            assertTrue(result.contains("**review**: Review code"))
        }

        @Test
        fun `verbose format uses XML style`() {
            registry.register(SkillInfo("review", "Review code", Path.of("/r/SKILL.md"), "content"))

            val result = registry.format(verbose = true)

            assertTrue(result.contains("<available_skills>"))
            assertTrue(result.contains("<name>review</name>"))
        }

        @Test
        fun `excludes skills without description`() {
            registry.register(SkillInfo("no-desc", null, Path.of("/x/SKILL.md"), "content"))

            val result = registry.format(verbose = false)
            assertTrue(result.isEmpty())
        }

        @Test
        fun `returns empty when no skills`() {
            val result = registry.format()
            assertTrue(result.isEmpty())
        }

        @Test
        fun `dirs tracks skill parent directories`() {
            registry.register(SkillInfo("test", "Test", Path.of("/some/path/SKILL.md"), "content"))
            val dirs = registry.dirs()
            assertTrue(dirs.any { it.toString().contains("/some/path") })
        }
    }
}