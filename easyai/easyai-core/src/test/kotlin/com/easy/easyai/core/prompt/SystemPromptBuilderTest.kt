package com.easy.easyai.core.prompt

import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SystemPromptBuilderTest {

    private val stubLoader = object : ProviderPromptLoader {
        override fun getPromptForProtocol(protocol: String): String = "Provider prompt for $protocol"
    }
    private val builder = SystemPromptBuilder(stubLoader)

    @Nested
    inner class `existing segments` {

        @Test
        fun `includes provider prompt when protocol is set`() {
            val config = AgentPromptConfig(protocol = "OPENAI")
            val segments = builder.build(config)
            assertTrue(segments.any { it.contains("Provider prompt for OPENAI") })
        }

        @Test
        fun `skips provider prompt when protocol is null`() {
            val config = AgentPromptConfig(protocol = null)
            val segments = builder.build(config)
            assertTrue(segments.none { it.contains("Provider prompt") })
        }

        @Test
        fun `includes custom instructions when present`() {
            val config = AgentPromptConfig(customInstructions = "Be concise.")
            val segments = builder.build(config)
            assertTrue(segments.any { it.contains("Be concise.") })
        }

        @Test
        fun `includes skills list when present`() {
            val config = AgentPromptConfig(skillsList = "- `code-review`: Review code")
            val segments = builder.build(config)
            assertTrue(segments.any { it.contains("code-review") })
        }

        @Test
        fun `includes sub-agents list when present`() {
            val config = AgentPromptConfig(subAgentsList = "- `explore`: Explore code")
            val segments = builder.build(config)
            assertTrue(segments.any { it.contains("explore") })
        }

        @Test
        fun `filters out empty segments`() {
            val config = AgentPromptConfig(
                protocol = null,
                customInstructions = null,
                skillsList = null,
                subAgentsList = null,
                instructionsSegment = null,
            )
            val segments = builder.build(config)
            // Should only contain mode segment + possibly env segment
            assertTrue(segments.none { it.isBlank() })
        }
    }

    @Nested
    inner class `instructions segment (segment 7)` {

        @Test
        fun `includes instructions segment when present`() {
            val instructions = "## Project Instructions\nDo not use println."
            val config = AgentPromptConfig(instructionsSegment = instructions)
            val segments = builder.build(config)
            assertTrue(segments.any { it.contains("Project Instructions") })
            assertTrue(segments.any { it.contains("Do not use println.") })
        }

        @Test
        fun `excludes instructions segment when null`() {
            val config = AgentPromptConfig(instructionsSegment = null)
            val segments = builder.build(config)
            assertTrue(segments.none { it.contains("Project Instructions") })
        }

        @Test
        fun `excludes instructions segment when blank`() {
            val config = AgentPromptConfig(instructionsSegment = "   ")
            val segments = builder.build(config)
            assertTrue(segments.none { it.contains("Project Instructions") })
        }

        @Test
        fun `instructions segment comes after sub-agents segment`() {
            val config = AgentPromptConfig(
                subAgentsList = "sub-agents content",
                instructionsSegment = "instructions content",
            )
            val segments = builder.build(config)
            val subAgentIdx = segments.indexOfFirst { it.contains("sub-agents content") }
            val instructionsIdx = segments.indexOfFirst { it.contains("instructions content") }
            assertTrue(subAgentIdx >= 0, "sub-agents segment should be present")
            assertTrue(instructionsIdx >= 0, "instructions segment should be present")
            assertTrue(instructionsIdx > subAgentIdx, "instructions should come after sub-agents")
        }
    }
}
