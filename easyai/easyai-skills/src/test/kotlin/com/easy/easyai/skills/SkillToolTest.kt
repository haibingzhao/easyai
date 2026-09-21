package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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

            every { mockRegistry.get("review", null) } returns skill
            every { mockRegistry.visibleFor(null) } returns listOf(skill)

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

            every { mockRegistry.get("format", null) } returns skill
            every { mockRegistry.visibleFor(null) } returns listOf(skill)

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
            every { mockRegistry.visibleFor(null) } returns emptyList()

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
            every { mockRegistry.get("unknown", null) } returns null
            every { mockRegistry.visibleFor(null) } returns listOf(
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
            // Whitelist gate runs before registry lookup (M10): the error message must not leak
            // skill names outside the caller's whitelist, so an unknown name gets the whitelist
            // hint rather than the registry's full visible list.
            assertTrue(result.content.any { it is TextContent && it.text.contains("Allowed skills: review, format") })
        }

        @Test
        fun `returns error when allowedSkillNames is empty (no skills authorized)`() = runTest {
            val skill = SkillInfo(
                name = "review",
                description = "Review code",
                location = Path.of("/skills/review/SKILL.md"),
                content = "Review instructions here.",
            )

            every { mockRegistry.get("review", null) } returns skill

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
            assertEquals(ToolExecutionMode.SEQUENTIAL, skillTool.executionMode)
        }
    }

    /**
     * The third authorization gate. The catalog row — addressed by (owner, name, granularity) —
     * is what proves the *requesting user* owns the skill at the requesting project, so a
     * whitelisted name is not yet proof of anything.
     */
    @Nested
    inner class CatalogGate {

        private val review = SkillInfo(
            name = "review",
            description = "Review code",
            location = Path.of("/skills/review/SKILL.md"),
            content = "Review instructions here."
        )

        private suspend fun CoroutineScope.gated(catalog: AsyncSkillCatalogStore?, userId: String? = "alice") = SkillTool(
            metadata = ToolMetadata(
                name = "load_skill",
                description = "Load a skill by name",
                permissionCategory = "skill",
                isDefaultTool = false
            ),
            registry = mockRegistry,
            allowedSkillNames = listOf("review"),
            catalog = catalog
        ).execute(
            agentContext = AgentContext(agentId = "test", userId = userId),
            toolCallId = "tc-1",
            args = mapOf("name" to "review"),
            coroutineScope = this,
            onUpdate = {}
        )

        private fun row(
            userId: String,
            enabled: Boolean = true,
            installPath: String = "/skills/review",
        ) = SkillCatalogEntry(
            id = "row-review-$userId",
            name = "review",
            checksum = "a".repeat(64),
            enabled = enabled,
            installPath = installPath,
            userId = userId
        )

        @Test
        fun `a whitelisted name the requester does not own is refused`() = runTest {
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            every { mockRegistry.get("review", null) } returns review
            coEvery { catalog.listByName(any(), any()) } returns emptyList()

            val result = gated(catalog)

            assertTrue(result.isError)
            assertTrue(
                result.content.any { it is TextContent && it.text.contains("not installed for this user") },
                "got: ${result.content}"
            )
        }

        @Test
        fun `a same-named skill owned by another user is never handed over`() = runTest {
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            every { mockRegistry.get("review", null) } returns review
            // alice owns nothing, so the gate may consult her rows and the shared ones - never bob's.
            coEvery { catalog.listByUser("alice") } returns emptyList()
            coEvery { catalog.listByName("review", SkillCatalogEntry.DEFAULT_USER_ID) } returns emptyList()

            val result = gated(catalog, userId = "alice")

            assertTrue(result.isError, "a name collision must not become a cross-user read")
            coVerify(exactly = 0) { catalog.listByName("review", "bob") }
            coVerify(exactly = 0) { catalog.listByUser("bob") }
        }

        @Test
        fun `a disabled row blocks loading until the owner switches it back on`() = runTest {
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            every { mockRegistry.get("review", null) } returns review
            coEvery { catalog.listByUser("alice") } returns listOf(row("alice", enabled = false))
            coEvery { catalog.listByName("review", "alice") } returns listOf(row("alice", enabled = false))

            val result = gated(catalog)

            assertTrue(result.isError)
            assertTrue(
                result.content.any { it is TextContent && it.text.contains("is disabled") },
                "got: ${result.content}"
            )
        }

        @Test
        fun `an owned and enabled row loads normally`() = runTest {
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            every { mockRegistry.get("review", null) } returns review
            coEvery { catalog.listByUser("alice") } returns listOf(row("alice"))
            coEvery { catalog.listByName("review", "alice") } returns listOf(row("alice"))

            val result = gated(catalog)

            assertFalse(result.isError)
            assertTrue(
                result.content.any { it is TextContent && it.text.contains("Review instructions here") },
                "got: ${result.content}"
            )
        }

        @Test
        fun `scanned server-level skills stay usable by a user with nothing of their own`() = runTest {
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            every { mockRegistry.get("review", null) } returns review
            coEvery { catalog.listByName("review", SkillCatalogEntry.DEFAULT_USER_ID) } returns
                listOf(row(SkillCatalogEntry.DEFAULT_USER_ID))

            val result = gated(catalog, userId = "carol")

            assertFalse(result.isError, "the default tenant is the fallback owner, not another user")
        }

        /**
         * The catalog row is authoritative for which directory a request may serve; when the
         * registry holds a stale or collided entry under a different location, the load is rejected
         * rather than handing over another tenant's bytes.
         */
        @Test
        fun `a registry hit pointing at another tenant's directory is rejected`() = runTest {
            val catalog = mockk<AsyncSkillCatalogStore>(relaxed = true)
            // alice's row is at /skills/review (matching the fixture), but the registry holds
            // bob's version of `review` under a different directory.
            every { mockRegistry.get("review", null) } returns review.copy(
                location = Path.of("/home/bob/.easyai/skills/review/SKILL.md")
            )
            coEvery { catalog.listByUser("alice") } returns listOf(row("alice"))
            coEvery { catalog.listByName("review", "alice") } returns listOf(row("alice"))

            val result = gated(catalog, userId = "alice")

            assertTrue(result.isError, "a registry/catalog location mismatch must never serve another tenant's content")
            assertTrue(
                result.content.any { it is TextContent && it.text.contains("out of sync with the catalog") },
                "got: ${result.content}"
            )
        }

        @Test
        fun `without a catalog the whitelist alone still decides, as before`() = runTest {
            every { mockRegistry.get("review", null) } returns review

            val result = gated(null)

            assertFalse(result.isError)
        }
    }
}