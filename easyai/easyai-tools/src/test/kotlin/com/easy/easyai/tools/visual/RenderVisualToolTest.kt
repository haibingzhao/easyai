package com.easy.easyai.tools.visual

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolMetadata
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    }
}
