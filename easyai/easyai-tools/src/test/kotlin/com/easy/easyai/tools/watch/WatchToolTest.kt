package com.easy.easyai.tools.watch

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchCreateInput
import com.easy.easyai.core.watch.WatchCreateResult
import com.easy.easyai.core.watch.WatchMode
import com.easy.easyai.core.watch.WatchNotifyPolicy
import com.easy.easyai.core.watch.WatchOrigin
import com.easy.easyai.core.watch.WatchSchedule
import com.easy.easyai.core.watch.WatchScheduleInput
import com.easy.easyai.core.watch.WatchScheduleKind
import com.easy.easyai.core.watch.WatchService
import com.easy.easyai.core.watch.WatchStatus
import com.easy.easyai.core.watch.WatchTag
import com.easy.easyai.core.watch.WatchUpdateInput
import com.easy.easyai.core.watch.WatchUpdateResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The `watch` tool is the model's only door into the subsystem. Its job here is narrow: resolve the
 * trigger the model chose, approve a safe read-only tool set, and hand the user a next-run time the
 * model did not have to calculate.
 */
class WatchToolTest {

    private val service = mockk<WatchService>(relaxed = true)
    private val originSlot = slot<WatchOrigin>()
    private val createInput = slot<WatchCreateInput>()
    private val updateInput = slot<WatchUpdateInput>()

    private val shanghai = ZoneId.of("Asia/Shanghai")

    private val watch = Watch(
        id = "watch-1",
        userId = "alice",
        title = "Price watch",
        condition = "Check the price",
        mode = WatchMode.CHECK,
        reminderText = null,
        schedule = WatchSchedule(WatchScheduleKind.DAILY, shanghai, localTime = LocalTime.of(9, 0)),
        untilAt = null,
        nextDueAt = Instant.parse("2026-11-01T01:00:00Z"),
        status = WatchStatus.ACTIVE,
        tags = listOf(WatchTag("crossed", "已突破")),
        completeOnMet = true,
        notifyPolicy = WatchNotifyPolicy.ON_CHANGE,
        notifyTags = emptySet(),
        sourceAgentId = "default-agent",
        modelConfigId = "model-1",
        projectId = null,
        projectPath = null,
        projectKind = ProjectKind.USER,
        owners = setOf("alice"),
        originSessionId = "session-1",
        runSessionId = "run-session",
        allowedTools = listOf("webfetch"),
        sourceHints = null,
        createdAt = 0,
        updatedAt = 0
    )

    private fun toolDef(
        name: String,
        capabilities: Set<ToolCapability> = emptySet(),
        tracksFiles: Boolean = false
    ): ToolDefinition = mockk {
        every { this@mockk.name } returns name
        every { this@mockk.capabilities } returns capabilities
        every { tracksFileChanges } returns tracksFiles
    }

    private val readTool = toolDef("webfetch", setOf(ToolCapability.READ_ONLY))
    private val searchTool = toolDef("memory_search", setOf(ToolCapability.READ_ONLY))
    private val shellTool = toolDef("bash", setOf(ToolCapability.SHELL_EXECUTION))
    private val mediaTool = toolDef("fetch_media", setOf(ToolCapability.READ_ONLY), tracksFiles = true)
    private val mcpTool = toolDef("market__quote")
    private val calcTool = toolDef("calc", setOf(ToolCapability.READ_ONLY))

    private val modelConfig = ModelProviderConfig(
        id = "model-1",
        name = "Test",
        protocol = ModelProviderInfo.Protocol.OPENAI,
        isCustom = false,
        modelId = "gpt-test"
    )

    private fun context(unattended: Boolean = false) = AgentContext(
        agentId = "default-agent",
        sessionId = "session-1",
        userId = "alice",
        modelConfig = modelConfig,
        tools = listOf(readTool, searchTool, shellTool, mediaTool, mcpTool, calcTool),
        timeZone = shanghai,
        unattended = unattended
    )

    private val tool: ToolDefinition = WatchTool(
        metadata = ToolMetadata(name = "watch", description = "Create and manage watches."),
        watchService = service
    )

    private fun execute(args: Map<String, Any?>, ctx: AgentContext = context()): ToolResult = runBlocking {
        tool.execute(
            agentContext = ctx,
            toolCallId = "call-1",
            args = args,
            coroutineScope = CoroutineScope(Job())
        )
    }

    private fun text(result: ToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    private fun givenCreated() {
        coEvery { service.create(capture(originSlot), capture(createInput)) } returns WatchCreateResult(watch, true)
        coEvery { service.update(any(), any(), capture(updateInput)) } returns WatchUpdateResult(watch)
        coEvery { service.get(any(), any()) } returns watch
        coEvery { service.list(any(), any(), any()) } returns listOf(watch)
    }

    @Nested
    inner class `create` {

        @Test
        fun `a relative trigger is resolved and the safe default tools are approved`() {
            givenCreated()
            val result = execute(
                mapOf(
                    "action" to "create",
                    "title" to "Price watch",
                    "condition" to "Check the price",
                    "within" to "2h",
                    "tags" to listOf(mapOf("key" to "crossed", "label" to "已突破"))
                )
            )

            assertFalse(result.isError)
            val input = createInput.captured
            assertEquals(WatchScheduleInput.In(Duration.ofHours(2)), input.schedule)
            assertEquals(shanghai, originSlot.captured.timeZone)
            assertEquals(listOf(WatchTag("crossed", "已突破")), input.tags)
            assertEquals(listOf("webfetch", "memory_search", "calc"), input.allowedTools)
            assertEquals(watch.nextDueAt.toEpochMilli(), result.details["nextRunAt"])
            assertEquals("2026-11-01 09:00 CST", result.details["nextRunLocal"])
            assertTrue(text(result).contains("Next run:"))
        }

        @Test
        fun `explicit MCP tools are accepted when the agent binds them`() {
            givenCreated()
            val result = execute(
                mapOf(
                    "action" to "create",
                    "title" to "Price watch",
                    "condition" to "Check the price",
                    "every" to "daily",
                    "time" to "09:00",
                    "allowedTools" to listOf("webfetch", "market__quote")
                )
            )

            assertFalse(result.isError)
            assertEquals(listOf("webfetch", "market__quote", "calc"), createInput.captured.allowedTools)
        }

        @Test
        fun `a shell tool is refused instead of silently dropped`() {
            val result = execute(
                mapOf(
                    "action" to "create",
                    "title" to "Price watch",
                    "condition" to "Check the price",
                    "within" to "2h",
                    "allowedTools" to listOf("bash")
                )
            )

            assertTrue(result.isError)
            assertTrue(text(result).contains("cannot run unattended"))
            coVerify(exactly = 0) { service.create(any(), any()) }
        }

        @Test
        fun `a tool this agent does not have is refused`() {
            val result = execute(
                mapOf(
                    "action" to "create",
                    "title" to "Price watch",
                    "condition" to "Check the price",
                    "within" to "2h",
                    "allowedTools" to listOf("missing_tool")
                )
            )

            assertTrue(result.isError)
            assertTrue(text(result).contains("not available to this agent"))
        }

        @Test
        fun `the default set warns when this agent has no read-only tools`() {
            givenCreated()
            val bare = context().copy(tools = listOf(shellTool))
            val result = execute(
                mapOf(
                    "action" to "create",
                    "title" to "Price watch",
                    "condition" to "Check the price",
                    "within" to "2h"
                ),
                bare
            )

            assertFalse(result.isError)
            assertEquals(emptyList(), createInput.captured.allowedTools)
            assertTrue((result.details["warnings"] as List<*>).any { it.toString().contains("No read-only") })
        }

        @Test
        fun `a watch created without a trigger fails with the accepted forms`() {
            val result = execute(mapOf("action" to "create", "title" to "x", "condition" to "y"))
            assertTrue(result.isError)
            assertTrue(text(result).contains("needs one trigger"))
        }

        @Test
        fun `an unattended run cannot create a watch`() {
            val result = execute(
                mapOf("action" to "create", "title" to "x", "condition" to "y", "within" to "2h"),
                context(unattended = true)
            )
            assertTrue(result.isError)
            assertTrue(text(result).contains("cannot create or edit"))
            coVerify(exactly = 0) { service.create(any(), any()) }
        }
    }

    @Nested
    inner class `update and list` {

        @Test
        fun `a text-only update leaves the schedule untouched`() {
            givenCreated()
            val result = execute(mapOf("action" to "update", "watchId" to "watch-1", "title" to "Renamed"))

            assertFalse(result.isError)
            assertNull(updateInput.captured.schedule)
            assertEquals("Renamed", updateInput.captured.title)
            assertTrue(text(result).contains("Next run:"))
        }

        @Test
        fun `a date change is resolved before it reaches the service`() {
            givenCreated()
            execute(
                mapOf(
                    "action" to "update",
                    "watchId" to "watch-1",
                    "dayOffset" to 1,
                    "time" to "09:00"
                )
            )
            assertEquals(
                WatchScheduleInput.DayOffset(1, LocalTime.of(9, 0)),
                updateInput.captured.schedule
            )
        }

        @Test
        fun `update without a watch id is refused`() {
            val result = execute(mapOf("action" to "update", "title" to "Renamed"))
            assertTrue(result.isError)
            assertTrue(text(result).contains("watchId"))
        }

        @Test
        fun `list returns the summary and structured rows`() {
            givenCreated()
            val result = execute(mapOf("action" to "list"))
            assertFalse(result.isError)
            assertTrue(text(result).contains("Price watch"))
            @Suppress("UNCHECKED_CAST")
            val rows = result.details["watches"] as List<Map<String, Any>>
            assertEquals("watch-1", rows.single()["watchId"])
        }
    }
}
