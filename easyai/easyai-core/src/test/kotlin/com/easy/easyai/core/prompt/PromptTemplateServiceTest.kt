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

    private fun contextWithTools(vararg toolNames: String): PromptContext = PromptContext(
        tools = toolNames.map { mapOf<String, Any?>("name" to it, "description" to "desc") }
    )

    @Nested
    inner class `time access guidance` {

        @Test
        fun `appends guidance when calc tool present on default prompt`() {
            val rendered = service.build(null, contextWithTools("read", "calc"))
            assertTrue(rendered.contains("## Current Time"))
            assertTrue(rendered.contains("ZonedDateTime.now().toString()"))
        }

        @Test
        fun `omits guidance when calc tool absent on default prompt`() {
            val rendered = service.build(null, contextWithTools("read", "bash"))
            assertFalse(rendered.contains("## Current Time"))
        }

        @Test
        fun `appends guidance on custom template when calc tool present`() {
            val rendered = service.build("You are a coding agent.", contextWithTools("calc"))
            assertTrue(rendered.contains("You are a coding agent."))
            assertTrue(rendered.contains("## Current Time"))
        }

        @Test
        fun `omits guidance on custom template when calc tool absent`() {
            val rendered = service.build("You are a coding agent.", contextWithTools("read"))
            assertFalse(rendered.contains("## Current Time"))
        }

        @Test
        fun `does not inject current date time into prompt`() {
            val rendered = service.build(null, contextWithTools("calc"))
            assertFalse(rendered.contains("Current date and time:"))
            assertFalse(rendered.contains("current_date_time"))
        }

        @Test
        fun `blank prompt template still returns empty string`() {
            val rendered = service.build("   ", contextWithTools("calc"))
            assertTrue(rendered.isEmpty())
        }
    }

    @Nested
    inner class `memory guidance` {

        @Test
        fun `appends static guidance when memory_search tool registered on default prompt`() {
            val rendered = service.build(null, contextWithTools("memory_search"))
            assertTrue(rendered.contains("## Memory"))
            assertTrue(rendered.contains("memory_search"))
        }

        @Test
        fun `omits guidance when memory_search tool not registered`() {
            val rendered = service.build(null, contextWithTools("read", "bash"))
            assertFalse(rendered.contains("## Memory"))
        }

        @Test
        fun `appends guidance on custom template when memory_search tool registered`() {
            val rendered = service.build("You are a coding agent.", contextWithTools("memory_search"))
            assertTrue(rendered.contains("You are a coding agent."))
            assertTrue(rendered.contains("## Memory"))
        }

        @Test
        fun `guidance output is stable across builds for cache friendliness`() {
            val context = contextWithTools("memory_search")
            val first = service.build(null, context)
            val second = service.build(null, context)
            assertTrue(first == second)
        }

        @Test
        fun `guidance covers stale entry update and remove rules`() {
            val guidance = service.build(null, contextWithTools("memory_search")).substringAfter("## Memory")
            assertTrue(guidance.contains("### Keeping memories current"), guidance)
            assertTrue(guidance.contains("action='update'"), guidance)
            assertTrue(guidance.contains("action='remove'"), guidance)
            // Staleness rules must stay free of concrete dates: injecting today would break
            // the cache stability asserted above.
            assertFalse(Regex("\\d{4}").containsMatchIn(guidance), guidance)
        }
    }

    @Nested
    inner class `knowledge guidance` {

        @Test
        fun `appends static guidance when knowledge_search tool registered on default prompt`() {
            val rendered = service.build(null, contextWithTools("knowledge_search"))
            assertTrue(rendered.contains("## Knowledge Base"))
            assertTrue(rendered.contains("knowledge_search"))
        }

        @Test
        fun `omits guidance when knowledge_search tool not registered`() {
            val rendered = service.build(null, contextWithTools("read", "bash"))
            assertFalse(rendered.contains("## Knowledge Base"))
        }

        @Test
        fun `guides parallel issuance together with memory search`() {
            val rendered = service.build(
                null,
                contextWithTools("memory_search", "knowledge_search")
            )
            assertTrue(rendered.contains("SAME response"))
        }

        @Test
        fun `guidance output is stable across builds for cache friendliness`() {
            val context = contextWithTools("knowledge_search")
            val first = service.build(null, context)
            val second = service.build(null, context)
            assertTrue(first == second)
        }
    }

    @Nested
    inner class `render visual guidance` {

        @Test
        fun `appends static guidance when render_visual tool registered on default prompt`() {
            val rendered = service.build(null, contextWithTools("render_visual"))
            assertTrue(rendered.contains("## Inline Visuals"))
            assertTrue(rendered.contains("render_visual"))
        }

        @Test
        fun `omits guidance when render_visual tool not registered`() {
            val rendered = service.build(null, contextWithTools("read", "bash"))
            assertFalse(rendered.contains("## Inline Visuals"))
        }

        @Test
        fun `appends guidance on custom template when render_visual tool registered`() {
            val rendered = service.build("You are a coding agent.", contextWithTools("render_visual"))
            assertTrue(rendered.contains("You are a coding agent."))
            assertTrue(rendered.contains("## Inline Visuals"))
        }

        @Test
        fun `guidance teaches mid-narrative placement instead of a trailing call`() {
            val guidance = service.build(null, contextWithTools("render_visual")).substringAfter("## Inline Visuals")
            assertTrue(guidance.contains("MIDDLE of your narrative"), guidance)
            assertTrue(guidance.contains("never repeat or summarize the fragment code"), guidance)
        }

        @Test
        fun `guidance output is stable across builds for cache friendliness`() {
            val context = contextWithTools("render_visual")
            val first = service.build(null, context)
            val second = service.build(null, context)
            assertTrue(first == second)
        }
    }

    @Nested
    inner class `sub-agent guidance` {

        private fun contextWith(
            toolNames: List<String>,
            subAgents: List<Map<String, Any?>> = emptyList()
        ): PromptContext = PromptContext(
            tools = toolNames.map { mapOf<String, Any?>("name" to it, "description" to "desc") },
            subAgents = subAgents
        )

        @Test
        fun `renders ad-hoc section when task tool present without predefined sub-agents`() {
            val rendered = service.build(null, contextWith(listOf("read", "task")))
            assertTrue(rendered.contains("## Available Sub-Agents"))
            assertTrue(rendered.contains("### Ad-hoc Sub-Agents"))
            assertTrue(rendered.contains("agentType \"dynamic\""))
        }

        @Test
        fun `renders predefined list and ad-hoc section together`() {
            val rendered = service.build(
                null,
                contextWith(
                    toolNames = listOf("task"),
                    subAgents = listOf(mapOf("name" to "coder", "description" to "writes code"))
                )
            )
            assertTrue(rendered.contains("`coder`: writes code"))
            assertTrue(rendered.contains("### Ad-hoc Sub-Agents"))
            assertTrue(rendered.contains("When delegating, provide a complete prompt"))
        }

        @Test
        fun `omits ad-hoc section when task tool absent but predefined sub-agents exist`() {
            val rendered = service.build(
                null,
                contextWith(
                    toolNames = listOf("read"),
                    subAgents = listOf(mapOf("name" to "coder", "description" to "writes code"))
                )
            )
            assertTrue(rendered.contains("`coder`: writes code"))
            assertFalse(rendered.contains("### Ad-hoc Sub-Agents"))
        }

        @Test
        fun `omits section entirely without task tool and sub-agents`() {
            val rendered = service.build(null, contextWith(listOf("read", "bash")))
            assertFalse(rendered.contains("## Available Sub-Agents"))
            assertFalse(rendered.contains("### Ad-hoc Sub-Agents"))
        }
    }
}
