package com.easy.easyai.core.prompt

import com.easy.easyai.common.textio.template.JinjavaTemplateRenderer
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PromptTemplateServiceTest {

    private val stubLoader = object : ProviderPromptLoader {
        override fun getPromptForProtocol(protocol: String): String = "Provider prompt for $protocol"
    }
    private val service = PromptTemplateService(
        renderer = JinjavaTemplateRenderer(),
        defaultBuilder = SystemPromptBuilder(stubLoader)
    )

    /**
     * PromptTemplateService no longer maps tool names to guidance text — that responsibility moved
     * to each tool's `systemPromptSegment` (collected by AgentLoopRunner into [PromptContext.toolPromptSegments])
     * and to `capabilities` (collected into [PromptContext.dynamicSubAgents]). These tests exercise the
     * mechanism: declared segments are appended verbatim, in order, for both default and custom templates.
     */
    private fun contextWith(
        tools: List<String> = emptyList(),
        segments: List<String> = emptyList(),
        subAgents: List<Map<String, Any?>> = emptyList(),
        dynamicSubAgents: Boolean = false
    ): PromptContext = PromptContext(
        tools = tools.map { mapOf<String, Any?>("name" to it, "description" to "desc") },
        toolPromptSegments = segments,
        subAgents = subAgents,
        dynamicSubAgents = dynamicSubAgents
    )

    @Nested
    inner class `tool prompt segments` {

        private val timeSegment = "## Current Time\nUse the calc tool for the current date."
        private val memorySegment = "## Memory\nRetrieve on demand via memory_search."

        @Test
        fun `appends a declared segment on the default prompt`() {
            val rendered = service.build(null, contextWith(tools = listOf("read", "calc"), segments = listOf(timeSegment)))
            assertTrue(rendered.contains("## Current Time"))
        }

        @Test
        fun `omits guidance when no segments declared on default prompt`() {
            val rendered = service.build(null, contextWith(tools = listOf("read", "bash")))
            assertFalse(rendered.contains("## Current Time"))
        }

        @Test
        fun `appends a declared segment on a custom template`() {
            val rendered = service.build("You are a coding agent.", contextWith(tools = listOf("calc"), segments = listOf(timeSegment)))
            assertTrue(rendered.contains("You are a coding agent."))
            assertTrue(rendered.contains("## Current Time"))
        }

        @Test
        fun `omits guidance on custom template when no segments declared`() {
            val rendered = service.build("You are a coding agent.", contextWith(tools = listOf("read")))
            assertFalse(rendered.contains("## Current Time"))
        }

        @Test
        fun `appends multiple declared segments in declaration order`() {
            val rendered = service.build(null, contextWith(segments = listOf(timeSegment, memorySegment)))
            assertTrue(rendered.contains("## Current Time"))
            assertTrue(rendered.contains("## Memory"))
            assertTrue(rendered.indexOf("## Current Time") < rendered.indexOf("## Memory"))
        }

        @Test
        fun `does not inject current date time into prompt`() {
            val rendered = service.build(null, contextWith(tools = listOf("calc"), segments = listOf(timeSegment)))
            assertFalse(rendered.contains("Current date and time:"))
            assertFalse(rendered.contains("current_date_time"))
        }

        @Test
        fun `blank prompt template still returns empty string`() {
            val rendered = service.build("   ", contextWith(tools = listOf("calc"), segments = listOf(timeSegment)))
            assertTrue(rendered.isEmpty())
        }

        @Test
        fun `segment output is stable across builds for cache friendliness`() {
            val context = contextWith(segments = listOf(memorySegment))
            val first = service.build(null, context)
            val second = service.build(null, context)
            assertTrue(first == second)
        }
    }

    @Nested
    inner class `sub-agent guidance` {

        @Test
        fun `renders ad-hoc section when a sub-agent spawner is present without predefined sub-agents`() {
            val rendered = service.build(null, contextWith(tools = listOf("read", "task"), dynamicSubAgents = true))
            assertTrue(rendered.contains("## Available Sub-Agents"))
            assertTrue(rendered.contains("### Ad-hoc Sub-Agents"))
            assertTrue(rendered.contains("agentType \"dynamic\""))
        }

        @Test
        fun `renders predefined list and ad-hoc section together`() {
            val rendered = service.build(
                null,
                contextWith(
                    tools = listOf("task"),
                    subAgents = listOf(mapOf("name" to "coder", "description" to "writes code")),
                    dynamicSubAgents = true
                )
            )
            assertTrue(rendered.contains("`coder`: writes code"))
            assertTrue(rendered.contains("### Ad-hoc Sub-Agents"))
            assertTrue(rendered.contains("When delegating, provide a complete prompt"))
        }

        @Test
        fun `omits ad-hoc section when no spawner but predefined sub-agents exist`() {
            val rendered = service.build(
                null,
                contextWith(
                    tools = listOf("read"),
                    subAgents = listOf(mapOf("name" to "coder", "description" to "writes code"))
                )
            )
            assertTrue(rendered.contains("`coder`: writes code"))
            assertFalse(rendered.contains("### Ad-hoc Sub-Agents"))
        }

        @Test
        fun `omits section entirely without spawner and sub-agents`() {
            val rendered = service.build(null, contextWith(tools = listOf("read", "bash")))
            assertFalse(rendered.contains("## Available Sub-Agents"))
            assertFalse(rendered.contains("### Ad-hoc Sub-Agents"))
        }
    }
}
