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
}
