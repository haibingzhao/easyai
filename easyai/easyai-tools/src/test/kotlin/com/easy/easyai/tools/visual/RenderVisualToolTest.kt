package com.easy.easyai.tools.visual

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests [RenderVisualTool]: execution only validates and acknowledges, because the
 * client renders the fragment from the tool-call arguments.
 */
class RenderVisualToolTest {

    private val tool = RenderVisualTool(
        metadata = ToolMetadata(name = "render_visual", description = "Render.", permissionCategory = "render_visual")
    )

    private fun execute(args: Map<String, Any?>) = runBlocking {
        tool.execute(
            agentContext = AgentContext(agentId = "test"),
            toolCallId = "tc-1",
            args = args,
            coroutineScope = this
        )
    }

    @Nested
    inner class Validation {
        @Test
        fun `blank title is rejected`() {
            val result = execute(mapOf("title" to "  ", "code" to "<svg></svg>"))
            assertTrue(result.isError)
        }

        @Test
        fun `blank code is rejected`() {
            val result = execute(mapOf("title" to "diagram", "code" to ""))
            assertTrue(result.isError)
        }

        @Test
        fun `fragment over the size cap is rejected`() {
            val oversized = "x".repeat((RenderVisualTool.MAX_FRAGMENT_BYTES + 1).toInt())
            val result = execute(mapOf("title" to "diagram", "code" to oversized))
            assertTrue(result.isError)
            assertTrue((result.content.first() as TextContent).text.contains("exceeds"))
        }
    }

    @Nested
    inner class Acknowledgement {
        @Test
        fun `valid fragment acknowledges delivery without repeating it`() {
            val result = execute(mapOf("title" to "diagram", "code" to "<svg viewBox=\"0 0 10 10\"></svg>"))
            assertFalse(result.isError)
            val text = (result.content.first() as TextContent).text
            assertTrue(text.contains("diagram"))
            assertTrue(text.contains("Do not repeat"))
        }
    }

    @Nested
    inner class Metadata {
        @Test
        fun `builder exposes auto-allow rule and default toolset membership`() {
            val builder = RenderVisualToolBuilder()
            assertEquals("render_visual", builder.metadata.name)
            assertTrue(builder.metadata.isDefaultTool)
            assertEquals(emptyList(), builder.metadata.patternKeys)
            assertEquals("tool.execute.render_visual", builder.defaultPermissionRules.single().permission)
        }

        @Test
        fun `builder declares mid-narrative guidance as system prompt segment`() {
            val segment = RenderVisualToolBuilder().metadata.systemPromptSegment
            assertTrue(segment != null)
            assertTrue(segment.contains("## Inline Visuals"), segment)
            assertTrue(segment.contains("MIDDLE of your narrative"), segment)
            assertTrue(segment.contains("never repeat or summarize the fragment code"), segment)
            assertTrue(segment.contains("ONE continuous document"), segment)
            assertTrue(segment.contains("never rewrite, renumber or re-polish sections already delivered"), segment)
        }
    }

    @Nested
    inner class ContextProjection {
        private val projector = RenderVisualToolBuilder().metadata.contextProjector!!

        /** Any fragment above the elision threshold; the exact size is asserted in the placeholder. */
        private val bulkyFragment = "<svg>" + "x".repeat(8 * 1024) + "</svg>"

        @Test
        fun `elides the code fragment but keeps title`() {
            val arguments = """{"title":"My Chart","code":"$bulkyFragment"}"""
            val projected = projector.project(arguments)
            assertTrue(projected.contains("\"title\":\"My Chart\""), projected)
            assertTrue(
                projected.contains("[fragment elided from context: ${bulkyFragment.length} bytes, rendered inline in the UI]"),
                projected
            )
            assertFalse(projected.contains("<svg>"), projected)
        }

        @Test
        fun `returns short arguments untouched without eliding`() {
            val arguments = """{"title":"My Chart","code":"<svg></svg>"}"""
            assertSame(arguments, projector.project(arguments))
        }

        @Test
        fun `passes arguments through when code is absent or not a string`() {
            val padding = "y".repeat(8 * 1024)
            assertEquals("""{"title":"x","pad":"$padding"}""", projector.project("""{"title":"x","pad":"$padding"}"""))
            assertEquals("""{"code":42,"pad":"$padding"}""", projector.project("""{"code":42,"pad":"$padding"}"""))
        }

        @Test
        fun `passes malformed json through unchanged`() {
            val malformed = "not-json" + "z".repeat(8 * 1024)
            assertEquals(malformed, projector.project(malformed))
        }
    }
}
