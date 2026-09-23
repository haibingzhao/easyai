package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [RefreshSkillsTool] — the step an agent calls after it has written a SKILL.md.
 *
 * The tool has no business arguments, so its whole value is the *answer*: a bare count ("registered=3")
 * leaves an agent guessing whether its own file made it, while naming what was added, what failed to
 * parse, and what still needs enabling is what lets it act. Most assertions are therefore on text.
 */
class RefreshSkillsToolTest {

    private val registry = mockk<SkillRegistry>()
    private val refresher = mockk<SkillRefreshService>()
    private val project = Path.of("/work/repo")

    private val metadata = ToolMetadata(
        name = "refresh_skills",
        description = "Make skills just written with `write` usable",
        permissionCategory = "skill",
        isDefaultTool = false,
        alwaysInclude = true
    )

    private fun tool(allowed: List<String> = emptyList(), withRefresher: Boolean = true) =
        RefreshSkillsTool(metadata, registry, if (withRefresher) refresher else null, allowed)

    private fun delta(added: List<String>, removed: List<String> = emptyList(), total: Int = added.size) =
        RegistryDelta(added = added, removed = removed, total = total)

    private fun outcome(
        added: List<String>,
        removed: List<String> = emptyList(),
        claimed: Int = 1,
        submitted: Int = 1,
        delisted: Int = 0,
        summaryPresent: Boolean = true
    ) = RefreshOutcome(
        delta = delta(added, removed, total = added.size),
        owner = "alice",
        claimed = claimed,
        submitted = submitted,
        summary = if (summaryPresent) ReconcileSummary(owners = 1, confirmed = 2, submitted = submitted, delisted = delisted) else null
    )

    /** Hands the tool a real [CoroutineScope], the way the agent loop does. */
    private suspend fun CoroutineScope.call(
        tool: RefreshSkillsTool,
        args: Map<String, Any?> = emptyMap(),
        userId: String? = "alice"
    ): String {
        val result: ToolResult = tool.execute(
            agentContext = AgentContext(agentId = "a", userId = userId, projectPath = project),
            toolCallId = "tc-1",
            args = args,
            coroutineScope = this,
            onUpdate = {}
        )
        assertFalse(result.isError, "a refresh must never fail the loop: ${result.firstText()}")
        return result.firstText()
    }

    private fun ToolResult.firstText(): String = content.filterIsInstance<TextContent>().joinToString("\n") { it.text }

    @Nested
    inner class `the parameter contract` {

        @Test
        fun `the one argument is optional and carries guidance`() {
            val schema = tool().inputSchema

            assertTrue(schema.contains("\"note\""), "the model only sees this schema: $schema")
            assertTrue(schema.contains("Optional one-line note"), "an undocumented argument gets invented: $schema")
        }

        @Test
        fun `the argument the schema declares is the argument the tool reads`() {
            // Guards against the drift where a schema advertises `dry_run` while the code reads `dryRun`:
            // the model obeys the schema, so this spelling is the one doExecute must use.
            val declared = Regex("\"properties\"\\s*:\\s*\\{\\s*\"([^\"]+)\"").find(tool().inputSchema)
                ?: error("no property in the generated schema: ${tool().inputSchema}")

            assertEquals("note", declared.groupValues[1])
        }

        @Test
        fun `it reads the skill directories, so it must not run beside another write`() {
            assertEquals(ToolExecutionMode.SEQUENTIAL, tool().executionMode)
            assertFalse(tool().tracksFileChanges, "it never touches the filesystem")
        }
    }

    @Nested
    inner class `without the catalog layer` {

        @Test
        fun `a re-scan is still worth reporting, with its blind spot named`() = runTest {
            every { registry.rescan(setOf(project)) } returns delta(listOf("pdf"), total = 4)

            val text = call(tool(withRefresher = false))

            verify(exactly = 1) { registry.rescan(setOf(project)) }
            coVerify(exactly = 0) { refresher.refreshFor(any(), any()) }
            assertTrue(text.contains("registered=4 added=[pdf]"), "got: $text")
            assertTrue(text.contains("Catalog coordination is unavailable"), "got: $text")
            assertTrue(text.contains("does not establish ownership or search readiness"), "got: $text")
        }

        @Test
        fun `a request with no identity still re-scans the session project`() = runTest {
            every { registry.rescan(setOf(project)) } returns delta(emptyList())

            call(tool(withRefresher = false), userId = null)

            verify(exactly = 1) { registry.rescan(setOf(project)) }
        }
    }

    @Nested
    inner class `with the catalog layer` {

        @Test
        fun `the refresh is asked for the requesting user and session project`() = runTest {
            coEvery { refresher.refreshFor("alice", project) } returns outcome(listOf("pdf"))

            val text = call(tool())

            coVerify(exactly = 1) { refresher.refreshFor("alice", project) }
            verify(exactly = 0) { registry.rescan(any()) }
            assertTrue(text.contains("owner 'alice'"), "the tenant the rows landed in must be visible: $text")
            assertTrue(text.contains("submitted=1"), "got: $text")
        }

        @Test
        fun `an index outage is reported, not hidden behind the counts`() = runTest {
            coEvery { refresher.refreshFor(any(), any()) } returns outcome(listOf("pdf"), summaryPresent = false)

            val text = call(tool())

            assertTrue(text.contains("could not be inspected"), "got: $text")
        }

        @Test
        fun `a submitted document is not claimed as already searchable`() = runTest {
            // The refresh never waits on the backend, so the answer must not imply the embedding finished:
            // an agent that trusts it would report a missing skill_search hit as a lost skill.
            coEvery { refresher.refreshFor(any(), any()) } returns outcome(listOf("pdf", "csv", "docx"), submitted = 3)

            val text = call(tool(allowed = listOf("pdf", "csv", "docx")))

            assertTrue(text.contains("submitted=3"), "got: $text")
            assertTrue(text.contains("Submitted is not searchable"), "got: $text")
            assertTrue(text.contains("target checksum confirmed processed"), "got: $text")
        }

        @Test
        fun `a clean refresh points at the verification step`() = runTest {
            coEvery { refresher.refreshFor(any(), any()) } returns outcome(listOf("pdf"))

            val text = call(tool(allowed = listOf("pdf")))

            assertTrue(text.contains("confirmed=2"), "got: $text")
            assertFalse(text.contains("The new skills are usable now"), "got: $text")
            assertTrue(text.contains("load_skill"), "the agent needs a next step: $text")
        }
    }

    @Nested
    inner class `what the agent may still get wrong` {

        @Test
        fun `a file that was not parsed is said plainly`() = runTest {
            coEvery { refresher.refreshFor(any(), any()) } returns outcome(emptyList(), claimed = 0, submitted = 0)

            val text = call(tool())

            assertTrue(text.contains("No new or changed source was registered"), "got: $text")
            assertTrue(text.contains("parse warnings"), "got: $text")
        }

        @Test
        fun `a body update is reported apart from new sources`() = runTest {
            coEvery { refresher.refreshFor(any(), any()) } returns RefreshOutcome(
                delta = RegistryDelta(added = emptyList(), total = 2, updatedKeys = listOf(SkillKey("pdf", project))),
                owner = "alice",
                claimed = 0,
                submitted = 1,
                summary = ReconcileSummary(owners = 1, updated = 1, submitted = 1)
            )

            val text = call(tool(allowed = listOf("pdf")))

            assertTrue(text.contains("updated=[pdf]"), "got: $text")
            assertTrue(text.contains("Content updated=1"), "got: $text")
            assertFalse(text.contains("No new or changed source"), "the source did change: $text")
        }

        @Test
        fun `a skill this agent has not been given is called out by name`() = runTest {
            coEvery { refresher.refreshFor(any(), any()) } returns outcome(listOf("pdf"))

            val text = call(tool(allowed = listOf("review")))

            assertTrue(text.contains("Not enabled for this agent yet: [pdf]"), "got: $text")
            assertTrue(text.contains("skill settings"), "the fix is a user action: $text")
        }

        @Test
        fun `skills that vanished from disk are listed so the user hears about them`() = runTest {
            coEvery { refresher.refreshFor(any(), any()) } returns outcome(listOf("pdf"), delisted = 2)

            val text = call(tool(allowed = listOf("pdf")))

            assertTrue(text.contains("deleted=2"), "got: $text")
            assertTrue(text.contains("pending="), "got: $text")
        }

        @Test
        fun `a pass that only removed skills still says what moved`() = runTest {
            coEvery { refresher.refreshFor(any(), any()) } returns RefreshOutcome(
                delta = RegistryDelta(added = emptyList(), removed = listOf("stale"), total = 1),
                owner = "alice",
                claimed = 0,
                submitted = 0,
                summary = ReconcileSummary(owners = 1)
            )

            val text = call(tool())

            assertTrue(text.contains("removed=[stale]"), "got: $text")
            assertTrue(text.contains("registered=1"), "got: $text")
        }
    }

    /**
     * The builder decides whether an agent can reach this tool at all, and that is a correctness
     * question rather than a preference: hidden behind a per-agent whitelist, nothing could publish
     * a SKILL.md it had just written.
     */
    @Nested
    inner class `how it is offered to agents` {

        private val registryProvider = mockk<ObjectProvider<SkillRegistry>>()
        private val refresherProvider = mockk<ObjectProvider<SkillRefreshService>>()

        private fun builder() = RefreshSkillsToolBuilder(registryProvider, refresherProvider)

        @Test
        fun `it reaches every agent without a whitelist entry and without asking permission`() {
            val metadata = builder().metadata

            assertEquals("refresh_skills", metadata.name)
            assertTrue(metadata.alwaysInclude, "an agent's stored toolNames would hide it forever")
            assertFalse(metadata.isDefaultTool, "it belongs to no fresh agent's default set")
            assertEquals(
                listOf(PermissionRule("tool.execute.skill", "*", PermissionAction.ALLOW)),
                builder().defaultPermissionRules,
                "an approval prompt on every call means the model simply stops calling it"
            )
        }

        @Test
        fun `with no skill registry there is nothing to re-read, so no tool is offered`() {
            every { registryProvider.getIfAvailable() } returns null

            assertNull(builder().build(AgentContext(agentId = "a"), mockk<AgentService>()))
        }

        @Test
        fun `the tool is handed the registry, the refresher and this agent's skill whitelist`() {
            val agentService = mockk<AgentService>()
            every { registryProvider.getIfAvailable() } returns registry
            every { refresherProvider.getIfAvailable() } returns refresher

            val built = builder().build(
                AgentContext(agentId = "a", userId = "alice", projectPath = project, allowedSkillNames = listOf("pdf")),
                agentService
            )

            assertNotNull(built)
            assertTrue(built is RefreshSkillsTool, "got: ${built.javaClass}")
        }

        @Test
        fun `the tool stays offered when the catalog layer is off`() {
            val agentService = mockk<AgentService>()
            every { registryProvider.getIfAvailable() } returns registry
            every { refresherProvider.getIfAvailable() } returns null

            assertNotNull(builder().build(AgentContext(agentId = "a"), agentService))
        }
    }

    @Nested
    inner class `arguments are tolerated either way` {

        @Test
        fun `a note is accepted without changing the answer`() = runTest {
            every { registry.rescan(setOf(project)) } returns delta(listOf("pdf"), total = 2)

            val text = call(tool(withRefresher = false), mapOf("note" to "after writing pdf-report"))

            assertTrue(text.contains("registered=2 added=[pdf]"), "got: $text")
        }

        @Test
        fun `a blank note is not an error`() = runTest {
            every { registry.rescan(setOf(project)) } returns delta(emptyList())

            assertTrue(call(tool(withRefresher = false), mapOf("note" to "  ")).isNotBlank())
        }
    }
}
