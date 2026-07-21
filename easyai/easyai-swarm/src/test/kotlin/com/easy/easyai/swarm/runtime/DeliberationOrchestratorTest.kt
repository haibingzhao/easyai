package com.easy.easyai.swarm.runtime

import com.easy.easyai.swarm.model.DeliberationEntry
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DeliberationOrchestratorTest {

    // ── A. extractJsonFromCodeBlock ──────────────────────────────────────

    @Nested
    inner class `extractJsonFromCodeBlock` {

        @Test
        fun `extracts JSON from markdown code block with json language tag`() {
            val input = """
                ```json
                {"converged": true, "reason": "done"}
                ```
            """.trimIndent()

            val result = extractJsonFromCodeBlock(input)

            assertEquals("""{"converged": true, "reason": "done"}""", result)
        }

        @Test
        fun `extracts JSON from markdown code block without language tag`() {
            val input = """
                ```
                {"converged": false}
                ```
            """.trimIndent()

            val result = extractJsonFromCodeBlock(input)

            assertEquals("""{"converged": false}""", result)
        }

        @Test
        fun `returns raw text when no code block present`() {
            val input = """{"converged": true, "reason": "no wrapper"}"""

            val result = extractJsonFromCodeBlock(input)

            assertEquals(input, result)
        }

        @Test
        fun `extracts first code block when multiple present`() {
            val input = """
                ```json
                {"first": true}
                ```
                Some text
                ```json
                {"second": true}
                ```
            """.trimIndent()

            val result = extractJsonFromCodeBlock(input)

            assertEquals("""{"first": true}""", result)
        }

        @Test
        fun `handles multiline JSON in code block`() {
            val input = """
                ```json
                {
                  "converged": false,
                  "reason": "more discussion needed",
                  "reviewer-a": "Please address the security concerns",
                  "reviewer-b": "What about performance?"
                }
                ```
            """.trimIndent()

            val result = extractJsonFromCodeBlock(input)

            assertTrue(result.contains("\"converged\": false"))
            assertTrue(result.contains("\"reviewer-a\""))
            assertTrue(result.contains("\"reviewer-b\""))
        }

        @Test
        fun `trims whitespace from extracted JSON`() {
            val input = "```json\n  {\"key\": \"value\"}  \n```"

            val result = extractJsonFromCodeBlock(input)

            assertEquals("""{"key": "value"}""", result)
        }
    }

    // ── B. formatDeliberationHistoryText ──────────────────────────────────

    @Nested
    inner class `formatDeliberationHistoryText` {

        @Test
        fun `returns placeholder for empty history`() {
            val result = formatDeliberationHistoryText(emptyList())

            assertTrue(result.contains("<deliberation_history>"))
            assertTrue(result.contains("(No history yet)"))
            assertTrue(result.contains("</deliberation_history>"))
        }

        @Test
        fun `formats single entry with XML tags`() {
            val entries = listOf(
                DeliberationEntry(
                    agentId = "reviewer-a",
                    round = 1,
                    response = "This proposal looks good overall."
                )
            )

            val result = formatDeliberationHistoryText(entries)

            assertTrue(result.contains("<deliberation_history>"))
            assertTrue(result.contains("<entry agent=\"reviewer-a\" round=\"1\">"))
            assertTrue(result.contains("This proposal looks good overall."))
            assertTrue(result.contains("</entry>"))
            assertTrue(result.contains("</deliberation_history>"))
        }

        @Test
        fun `formats multiple entries as separate XML elements`() {
            val entries = listOf(
                DeliberationEntry("reviewer-a", 1, "First opinion"),
                DeliberationEntry("reviewer-b", 1, "Second opinion"),
                DeliberationEntry("reviewer-a", 2, "Follow-up"),
            )

            val result = formatDeliberationHistoryText(entries)

            assertTrue(result.contains("<entry agent=\"reviewer-a\" round=\"1\">"))
            assertTrue(result.contains("First opinion"))
            assertTrue(result.contains("<entry agent=\"reviewer-b\" round=\"1\">"))
            assertTrue(result.contains("Second opinion"))
            assertTrue(result.contains("<entry agent=\"reviewer-a\" round=\"2\">"))
            assertTrue(result.contains("Follow-up"))
            // Count entry elements
            val entryCount = Regex("<entry ").findAll(result).count()
            assertEquals(3, entryCount)
        }

        @Test
        fun `preserves agent ID ordering within a round`() {
            val entries = listOf(
                DeliberationEntry("alpha", 1, "Alpha says"),
                DeliberationEntry("beta", 1, "Beta says"),
                DeliberationEntry("gamma", 1, "Gamma says"),
            )

            val result = formatDeliberationHistoryText(entries)

            val alphaPos = result.indexOf("agent=\"alpha\"")
            val betaPos = result.indexOf("agent=\"beta\"")
            val gammaPos = result.indexOf("agent=\"gamma\"")
            assertTrue(alphaPos < betaPos, "alpha should appear before beta")
            assertTrue(betaPos < gammaPos, "beta should appear before gamma")
        }
    }
}
