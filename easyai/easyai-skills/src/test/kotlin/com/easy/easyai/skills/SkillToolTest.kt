package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolMetadata
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

class SkillToolTest {

    private lateinit var mockRegistry: SkillRegistry
    private lateinit var skillTool: SkillTool
    private lateinit var allowedSkillTool: SkillTool
    private val testContext = AgentContext(agentId = "test")

    @BeforeEach
    fun setUp() {
        mockRegistry = mockk()
        skillTool = SkillTool(
            metadata = ToolMetadata(
                name = "load_skill",
                description = "Load a skill by name",
                permissionCategory = "skill",
                isDefaultTool = false
            ),
            registry = mockRegistry
        )
        allowedSkillTool = SkillTool(
            metadata = ToolMetadata(
                name = "load_skill",
                description = "Load a skill by name",
                permissionCategory = "skill",
                isDefaultTool = false
            ),
            registry = mockRegistry,
            allowedSkillNames = listOf("review", "format")
        )
    }

    @Nested
    inner class ExecuteWithValidSkillName {
        @Test
        fun `returns skill content`() = runTest {
            val skill = SkillInfo(
                name = "review",
                description = "Review code",
                location = Path.of("/skills/review/SKILL.md"),
                content = "Review instructions here.",
                tags = setOf("coding"),
            )

            every { mockRegistry.get("review") } returns skill
            every { mockRegistry.all() } returns listOf(skill)

            val result = allowedSkillTool.execute(
                agentContext = testContext,
                toolCallId = "tc-1",
                args = mapOf("name" to "review"),
                coroutineScope = this,
                onUpdate = {},
            )

            assertTrue(result.content.any { it is TextContent && it.text.contains("Review instructions here") })
        }

        @Test
        fun `includes description and tags in output`() = runTest {
            val skill = SkillInfo(
                name = "format",
                description = "Format code",
                location = Path.of("/skills/format/SKILL.md"),
                content = "Format rules",
                tags = setOf("coding", "style"),
            )

            every { mockRegistry.get("format") } returns skill
            every { mockRegistry.all() } returns listOf(skill)

            val result = allowedSkillTool.execute(
                agentContext = testContext,
                toolCallId = "tc-1",
                args = mapOf("name" to "format"),
                coroutineScope = this,
                onUpdate = {},
            )

            val textContent = result.content.filterIsInstance<TextContent>().firstOrNull()?.text ?: ""
            assertTrue(textContent.contains("Format code"))
            assertTrue(textContent.contains("coding, style"))
        }
    }

    @Nested
    inner class ExecuteWithInvalidSkillName {
        @Test
        fun `returns error for blank name`() = runTest {
            every { mockRegistry.all() } returns emptyList()

            val result = skillTool.execute(
                agentContext = testContext,
                toolCallId = "tc-1",
                args = mapOf("name" to ""),
                coroutineScope = this,
                onUpdate = {},
            )

            assertTrue(result.isError)
            assertTrue(result.content.any { it is TextContent && it.text.contains("required") })
        }

        @Test
        fun `returns error for unknown skill`() = runTest {
            every { mockRegistry.get("unknown") } returns null
            every { mockRegistry.all() } returns listOf(
                SkillInfo("review", "Review", Path.of("/r/SKILL.md"), "content")
            )

            val result = allowedSkillTool.execute(
                agentContext = testContext,
                toolCallId = "tc-1",
                args = mapOf("name" to "unknown"),
                coroutineScope = this,
                onUpdate = {},
            )

            assertTrue(result.isError)
            assertTrue(result.content.any { it is TextContent && it.text.contains("Available skills: review") })
        }

        @Test
        fun `returns error when allowedSkillNames is empty (no skills authorized)`() = runTest {
            val skill = SkillInfo(
                name = "review",
                description = "Review code",
                location = Path.of("/skills/review/SKILL.md"),
                content = "Review instructions here.",
            )

            every { mockRegistry.get("review") } returns skill

            // skillTool has empty allowedSkillNames (default) → all skills blocked
            val result = skillTool.execute(
                agentContext = testContext,
                toolCallId = "tc-1",
                args = mapOf("name" to "review"),
                coroutineScope = this,
                onUpdate = {},
            )

            assertTrue(result.isError)
            assertTrue(result.content.any { it is TextContent && it.text.contains("No skills are authorized") })
        }
    }

    @Nested
    inner class ToolMetadata {
        @Test
        fun `name is load_skill`() {
            assertEquals("load_skill", skillTool.name)
        }

        @Test
        fun `description is non-empty`() {
            assertTrue(skillTool.description.isNotBlank())
        }

        @Test
        fun `execution mode is sequential`() {
            assertEquals(
                com.easy.easyai.core.tool.ToolExecutionMode.SEQUENTIAL,
                skillTool.executionMode
            )
        }
    }
}