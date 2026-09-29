package com.easy.easyai.skills.subagent

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StubTool(
    override val name: String,
    override val permissionCategory: String = name
) : ToolDefinition {
    override val description: String = "stub tool"
    override val inputSchema: String = "{}"
    override suspend fun execute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = ToolResult(content = listOf(TextContent("ok")))
}

class SubAgentToolDynamicTest {

    private fun tool(
        store: AsyncAgentStore = mockk(relaxed = true),
        service: AgentService = mockk(relaxed = true),
        listenerFactory: ((String, AgentContext, String, String) -> MessageListener?)? = null
    ) = SubAgentTool(
        metadata = ToolMetadata("task", "Delegate to sub-agent", permissionCategory = "subagent"),
        agentStore = store,
        agentService = service,
        contextResolver = null,
        subAgentMessageListenerFactory = listenerFactory
    )

    private val parentTools = listOf(
        StubTool("read"),
        StubTool("bash"),
        StubTool("task"),
        StubTool("ask_question"),
        StubTool("github__create_issue", permissionCategory = "mcp"),
        StubTool("github__list_prs", permissionCategory = "mcp"),
        StubTool("slack__post_message", permissionCategory = "mcp"),
    )

    private fun parentContext(
        allowedSkillNames: List<String> = listOf("review", "deploy"),
        sessionId: String? = null
    ) = AgentContext(
        agentId = "parent-agent",
        sessionId = sessionId,
        tools = parentTools,
        allowedSkillNames = allowedSkillNames,
        skills = listOf(
            mapOf("name" to "review", "description" to "code review"),
            mapOf("name" to "deploy", "description" to "deploy flow"),
        ),
        subAgents = emptyList()
    )

    private fun text(result: ToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString { it.text }

    private fun rejectMessage(spec: SubAgentTool.AgentSpec?, context: AgentContext): String {
        val resolution = tool().resolveDynamicSubAgent(spec, context)
        assertIs<SubAgentTool.DynamicResolution.Rejected>(resolution)
        return resolution.message
    }

    private fun resolve(spec: SubAgentTool.AgentSpec, context: AgentContext) =
        tool().resolveDynamicSubAgent(spec, context)

    @Nested
    inner class `DynamicSpecValidation` {

        @Test
        fun `missing agentSpec is rejected`() {
            val message = rejectMessage(null, parentContext())
            assertTrue(message.contains("requires an 'agentSpec'"), message)
        }

        @Test
        fun `blank name is rejected`() {
            val message = rejectMessage(SubAgentTool.AgentSpec(name = "  "), parentContext())
            assertTrue(message.contains("non-blank"), message)
        }

        @Test
        fun `toolNames outside parent tools are rejected with available list`() {
            val message = rejectMessage(
                SubAgentTool.AgentSpec(name = "worker", toolNames = listOf("read", "write")),
                parentContext()
            )
            assertTrue(message.contains("[write]"), message)
            assertTrue(message.contains("subset of the parent agent's tools"), message)
            assertTrue(message.contains("read"), message)
        }

        @Test
        fun `skillNames outside parent whitelist are rejected`() {
            val message = rejectMessage(
                SubAgentTool.AgentSpec(name = "worker", skillNames = listOf("review", "secret-skill")),
                parentContext()
            )
            assertTrue(message.contains("[secret-skill]"), message)
            assertTrue(message.contains("Authorized skill names"), message)
        }

        @Test
        fun `any skillNames rejected when parent has no authorized skills`() {
            val message = rejectMessage(
                SubAgentTool.AgentSpec(name = "worker", skillNames = listOf("review")),
                parentContext(allowedSkillNames = emptyList())
            )
            assertTrue(message.contains("no authorized skills; omit skillNames"), message)
        }

        @Test
        fun `unknown mcpServerNames are rejected with available list`() {
            val message = rejectMessage(
                SubAgentTool.AgentSpec(name = "worker", mcpServerNames = listOf("github", "jira")),
                parentContext()
            )
            assertTrue(message.contains("[jira]"), message)
            assertTrue(message.contains("Available MCP servers"), message)
        }

        @Test
        fun `mcpServerNames rejected when parent has no MCP servers`() {
            val context = AgentContext(agentId = "parent", tools = listOf(StubTool("read")))
            val message = rejectMessage(
                SubAgentTool.AgentSpec(name = "worker", mcpServerNames = listOf("github")),
                context
            )
            assertTrue(message.contains("no MCP servers; omit mcpServerNames"), message)
        }

        @Test
        fun `valid subset resolves`() {
            val resolution = resolve(
                SubAgentTool.AgentSpec(
                    name = "worker",
                    toolNames = listOf("read"),
                    skillNames = listOf("review"),
                    mcpServerNames = listOf("github")
                ),
                parentContext()
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
        }

        @Test
        fun `mcpServerNames are sanitized before matching`() {
            val context = AgentContext(
                agentId = "parent",
                tools = listOf(StubTool("my_server__do_thing", permissionCategory = "mcp"))
            )
            val resolution = resolve(
                SubAgentTool.AgentSpec(name = "worker", mcpServerNames = listOf("my-server")),
                context
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            assertEquals(listOf("my_server__do_thing"), resolution.tools.map { it.name })
        }
    }

    @Nested
    inner class `DynamicToolResolution` {

        @Test
        fun `parent tools are filtered to the requested subset`() {
            val resolution = resolve(
                SubAgentTool.AgentSpec(name = "worker", toolNames = listOf("read"), mcpServerNames = emptyList()),
                parentContext()
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            assertEquals(listOf("read"), resolution.tools.map { it.name })
        }

        @Test
        fun `requested MCP server brings all its tools only`() {
            val resolution = resolve(
                SubAgentTool.AgentSpec(name = "worker", toolNames = emptyList(), mcpServerNames = listOf("github")),
                parentContext()
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            assertEquals(
                listOf("github__create_issue", "github__list_prs"),
                resolution.tools.map { it.name }
            )
        }

        @Test
        fun `forbidden tools are stripped even when requested`() {
            val resolution = resolve(
                SubAgentTool.AgentSpec(
                    name = "worker",
                    toolNames = listOf("read", "task", "ask_question"),
                    mcpServerNames = emptyList()
                ),
                parentContext()
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            assertEquals(listOf("read"), resolution.tools.map { it.name })
        }

        @Test
        fun `skills and whitelist are narrowed to the requested subset`() {
            val resolution = resolve(
                SubAgentTool.AgentSpec(name = "worker", skillNames = listOf("review")),
                parentContext()
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            assertEquals(listOf("review"), resolution.baseContext.allowedSkillNames)
            assertEquals(listOf("review"), resolution.baseContext.skills.map { it["name"] })
            assertTrue(resolution.baseContext.subAgents.isEmpty())
            assertTrue(resolution.baseContext.mcpConfigs.isEmpty())
            assertNull(resolution.baseContext.customInstructions)
        }

        @Test
        fun `omitting resource selections inherits all parent resources minus forbidden`() {
            val resolution = resolve(SubAgentTool.AgentSpec(name = "clone"), parentContext())
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            assertEquals(
                listOf("read", "bash", "github__create_issue", "github__list_prs", "slack__post_message"),
                resolution.tools.map { it.name }
            )
            assertEquals(listOf("review", "deploy"), resolution.baseContext.allowedSkillNames)
            assertEquals(listOf("review", "deploy"), resolution.baseContext.skills.map { it["name"] })
        }

        @Test
        fun `explicit empty arrays grant no resources`() {
            val resolution = resolve(
                SubAgentTool.AgentSpec(
                    name = "thinker",
                    toolNames = emptyList(),
                    skillNames = emptyList(),
                    mcpServerNames = emptyList()
                ),
                parentContext()
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            assertTrue(resolution.tools.isEmpty())
            assertTrue(resolution.baseContext.allowedSkillNames.isEmpty())
            assertTrue(resolution.baseContext.skills.isEmpty())
        }
    }

    @Nested
    inner class `DynamicDefinitionSynthesis` {

        @Test
        fun `definition carries dynamic id prefix and sub-agent type`() {
            val resolution = resolve(
                SubAgentTool.AgentSpec(name = "worker", description = "does work", systemPrompt = "You are a worker."),
                parentContext()
            )
            assertIs<SubAgentTool.DynamicResolution.Resolved>(resolution)
            val definition = resolution.definition
            assertTrue(definition.id.startsWith("dynamic:"), definition.id)
            assertEquals("worker", definition.name)
            assertEquals("does work", definition.description)
            assertEquals(AgentType.SUBAGENT, definition.agentType)
            assertEquals("You are a worker.", definition.promptTemplate)
            assertNull(definition.inputSchema)
            assertEquals(definition.id, resolution.baseContext.agentId)
        }

        @Test
        fun `maxIterations defaults and coerces into bounds`() {
            val default = resolve(SubAgentTool.AgentSpec(name = "w"), parentContext())
            assertIs<SubAgentTool.DynamicResolution.Resolved>(default)
            assertEquals(SubAgentTool.DEFAULT_DYNAMIC_MAX_ITERATIONS, default.definition.maxIterations)

            val capped = resolve(SubAgentTool.AgentSpec(name = "w", maxIterations = 999), parentContext())
            assertIs<SubAgentTool.DynamicResolution.Resolved>(capped)
            assertEquals(SubAgentTool.MAX_DYNAMIC_MAX_ITERATIONS, capped.definition.maxIterations)

            val floored = resolve(SubAgentTool.AgentSpec(name = "w", maxIterations = 0), parentContext())
            assertIs<SubAgentTool.DynamicResolution.Resolved>(floored)
            assertEquals(1, floored.definition.maxIterations)
        }
    }

    @Nested
    inner class `DynamicExecution` {

        @Test
        fun `agentSpec combined with predefined agentType is rejected`() = runTest {
            val context = parentContext().copy(
                subAgents = listOf(mapOf("id" to "pre1", "name" to "pre-agent", "description" to "d"))
            )
            val result = tool().execute(
                agentContext = context,
                toolCallId = "tc-1",
                messageId = null,
                args = mapOf(
                    "prompt" to "do work",
                    "agentType" to "pre1",
                    "agentSpec" to mapOf("name" to "worker")
                ),
                coroutineScope = this,
                onUpdate = {}
            )
            assertTrue(result.isError)
            assertTrue(text(result).contains("cannot be used with a predefined agentType"), text(result))
        }

        @Test
        fun `unknown agentType error hints at dynamic creation`() = runTest {
            val result = tool().execute(
                agentContext = parentContext(),
                toolCallId = "tc-1",
                messageId = null,
                args = mapOf("prompt" to "do work", "agentType" to "ghost"),
                coroutineScope = this,
                onUpdate = {}
            )
            assertTrue(result.isError)
            assertTrue(text(result).contains("agentType 'dynamic'"), text(result))
        }

        @Test
        fun `dynamic path builds sub-agent context from parent resources`() = runTest {
            var captured: AgentContext? = null
            val failingService = mockk<AgentService> {
                every { defaultChatModel } throws IllegalStateException("boom")
            }
            val underTest = tool(
                service = failingService,
                listenerFactory = { _, ctx, _, _ -> captured = ctx; null }
            )
            val context = parentContext(sessionId = "s-1")

            assertFailsWith<IllegalStateException> {
                underTest.execute(
                    agentContext = context,
                    toolCallId = "tc-9",
                    messageId = "msg-1",
                    args = mapOf(
                        "prompt" to "analyze things",
                        "agentType" to "dynamic",
                        "agentSpec" to mapOf(
                            "name" to "analyst",
                            "systemPrompt" to "You are an analyst.",
                            "toolNames" to listOf("read", "bash"),
                            "skillNames" to listOf("review"),
                            "mcpServerNames" to listOf("github")
                        )
                    ),
                    coroutineScope = this,
                    onUpdate = {}
                )
            }

            val subContext = captured
            assertTrue(subContext != null, "sub-agent context should be captured before execution")
            assertEquals("parent-agent", subContext.parentAgentId)
            assertEquals("tc-9", subContext.agentRunId)
            assertTrue(subContext.subAgents.isEmpty())
            assertEquals("You are an analyst.", subContext.promptTemplate)
            assertEquals(listOf("review"), subContext.allowedSkillNames)
            assertEquals(
                listOf("read", "bash", "github__create_issue", "github__list_prs"),
                subContext.tools.map { it.name }
            )
        }
    }
}
