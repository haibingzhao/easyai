package com.easy.easyai.core.agent

import com.easy.easyai.common.util.SharedObjectMapper
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

internal class McpBindingSpecTest {

    @Nested
    inner class Parsing {

        @Test
        fun `reads legacy array format as tool whitelist only`() {
            val spec = McpBindingSpec.parse("""["read","write"]""")
            assertEquals(listOf("read", "write"), spec.toolNames)
            assertEquals(emptyList(), spec.promptNames)
        }

        @Test
        fun `reads object format with both whitelists`() {
            val spec = McpBindingSpec.parse("""{"toolNames":["read"],"promptNames":["summarize"]}""")
            assertEquals(listOf("read"), spec.toolNames)
            assertEquals(listOf("summarize"), spec.promptNames)
        }

        @Test
        fun `object format with a single side leaves the other empty`() {
            assertEquals(emptyList(), McpBindingSpec.parse("""{"toolNames":["read"]}""").promptNames)
            assertEquals(emptyList(), McpBindingSpec.parse("""{"promptNames":["summarize"]}""").toolNames)
        }

        @Test
        fun `absent blank and malformed metadata all allow the whole server`() {
            for (metadata in listOf(null, "", "   ", "not-json", "{broken")) {
                assertEquals(McpBindingSpec(), McpBindingSpec.parse(metadata))
                assertNull(McpBindingSpec.parseOrNull(metadata))
            }
        }

        @Test
        fun `valid but empty object parses to an empty spec rather than null`() {
            assertEquals(McpBindingSpec(), McpBindingSpec.parseOrNull("{}"))
        }
    }

    @Nested
    inner class Rendering {

        @Test
        fun `round-trips an object payload`() {
            val metadata = McpBindingSpec(listOf("read"), listOf("summarize")).toMetadata()
            assertEquals(McpBindingSpec(listOf("read"), listOf("summarize")), McpBindingSpec.parse(metadata))
        }

        @Test
        fun `writes null when nothing is whitelisted`() {
            assertNull(McpBindingSpec().toMetadata())
        }

        @Test
        fun `omits a whitelist that is empty`() {
            val node = SharedObjectMapper.instance.readTree(McpBindingSpec(promptNames = listOf("summarize")).toMetadata())
            assertNull(node.get("toolNames"))
            assertEquals("summarize", node.get("promptNames")[0].asString())
        }
    }

    @Nested
    inner class InlineNode {

        @Test
        fun `reads whitelists straight from a deserialized mcp entry`() {
            val node = SharedObjectMapper.instance.readTree("""{"serverName":"s1","toolNames":["read"],"promptNames":["p"]}""")
            assertEquals(McpBindingSpec(listOf("read"), listOf("p")), McpBindingSpec.fromNode(node))
        }

        @Test
        fun `missing node yields an empty spec`() {
            assertEquals(McpBindingSpec(), McpBindingSpec.fromNode(null))
        }
    }
}
