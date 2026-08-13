package com.easy.easyai.core.message

import com.easy.easyai.core.model.ToolResultEntry
import org.junit.jupiter.api.AfterEach
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for [ToolResultGuard]: generation-point spill of oversized tool results to the temp dir,
 * with truncation as the fallback path.
 */
class ToolResultGuardTest {

    @AfterEach
    fun resetSpillDir() {
        ToolResultGuard.spillDirOverride = null
    }

    @Nested
    inner class `guard` {

        @Test
        fun `passes through results within the limit`() {
            val result = "small output"
            val guarded = ToolResultGuard.guard(result, maxChars = 100)
            assertEquals(result, guarded.text)
            assertFalse(guarded.truncated)
        }

        @Test
        fun `passes through results exactly at the limit`() {
            val result = "x".repeat(100)
            val guarded = ToolResultGuard.guard(result, maxChars = 100)
            assertEquals(result, guarded.text)
            assertFalse(guarded.truncated)
        }

        @Test
        fun `truncates oversized results keeping head and tail`() {
            val head = "H".repeat(600)
            val middle = "M".repeat(1_000)
            val tail = "T".repeat(400)
            val guarded = ToolResultGuard.guard(head + middle + tail, maxChars = 1_000)

            assertTrue(guarded.truncated)
            // The marker consumes part of the budget; head keeps 60% of the remainder
            val marker = "\n... [output truncated: 1000 chars omitted] ...\n"
            val budget = 1_000 - marker.length
            val headKeep = (budget * 0.6).toInt()
            val tailKeep = budget - headKeep
            assertTrue(guarded.text.startsWith("H".repeat(headKeep)))
            assertTrue(guarded.text.endsWith("T".repeat(tailKeep)))
            assertTrue(guarded.text.contains("[output truncated: 1000 chars omitted]"))
            assertFalse(guarded.text.contains("M"), "middle content must be dropped")
            assertTrue(guarded.text.length <= 1_000, "guarded text must respect the cap, marker included")
        }

        @Test
        fun `returns empty guarded text for non-positive limits`() {
            val guarded = ToolResultGuard.guard("some output", maxChars = 0)
            assertTrue(guarded.truncated)
            assertEquals("", guarded.text)
        }

        @Test
        fun `truncation is deterministic`() {
            val input = "a".repeat(5_000) + "b".repeat(5_000)
            val first = ToolResultGuard.guard(input, maxChars = 1_000)
            val second = ToolResultGuard.guard(input, maxChars = 1_000)
            assertEquals(first.text, second.text)
        }
    }

    @Nested
    inner class `guardEntry` {

        @Test
        fun `returns the same entry when no spill is needed`() {
            val entry = ToolResultEntry(toolCallId = "call_1", toolName = "read", result = "short")
            assertSame(entry, runBlocking { ToolResultGuard.guardEntry(entry) })
        }

        @Test
        fun `spills oversized results to the temp dir and replaces the result with a notice`(@TempDir tempDir: Path) {
            ToolResultGuard.spillDirOverride = tempDir
            val content = "line1\n" + "x".repeat(2_000) + "\nlast"
            val entry = ToolResultEntry(toolCallId = "call_2", toolName = "search", result = content)

            val guarded = runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 1_000) }

            assertTrue(guarded.truncated)
            assertEquals("call_2", guarded.toolCallId)
            assertEquals("search", guarded.toolName)
            // Full content persisted to exactly one file
            val files = Files.list(tempDir).use { it.toList() }
            assertEquals(1, files.size, "exactly one spill file expected")
            assertEquals(content, Files.readString(files.single()), "spill file must hold the full original content")
            // Notice carries the pointer and guidance
            assertTrue(guarded.result.contains("saved to"))
            assertTrue(guarded.result.contains(files.single().fileName.toString()))
            assertTrue(guarded.result.contains("(3 lines)"))
            assertTrue(guarded.result.contains("RE-RUN the tool"))
            assertTrue(guarded.result.contains("Access patterns"))
            assertTrue(guarded.result.contains("Sample of the data"))
        }

        @Test
        fun `file name uses sanitized toolCallId and content hash`(@TempDir tempDir: Path) {
            ToolResultGuard.spillDirOverride = tempDir
            val content = "y".repeat(2_000)
            val entry = ToolResultEntry(toolCallId = "call/with:weird chars", toolName = "read", result = content)

            runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 100) }

            val fileName = Files.list(tempDir).use { it.toList() }.single().fileName.toString()
            assertTrue(fileName.matches(Regex("""call_with_weird_chars_[0-9a-f]{8}\.txt""")),
                "unexpected file name: $fileName")
        }

        @Test
        fun `spill is idempotent for the same toolCallId and content`(@TempDir tempDir: Path) {
            ToolResultGuard.spillDirOverride = tempDir
            val content = "z".repeat(2_000)
            val entry = ToolResultEntry(toolCallId = "call_4", toolName = "search", result = content)

            runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 100) }
            runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 100) }

            assertEquals(1, Files.list(tempDir).use { it.count() }, "repeated spills must not duplicate files")
            assertEquals(content, Files.readString(Files.list(tempDir).use { it.toList().single() }))
        }

        @Test
        fun `falls back to truncation when spilling fails`(@TempDir tempDir: Path) {
            // Point the spill dir at an existing FILE: createDirectories throws, spill fails,
            // and guardEntry must degrade to head+tail truncation instead of breaking.
            val blocker = tempDir.resolve("not-a-dir")
            Files.writeString(blocker, "occupied")
            ToolResultGuard.spillDirOverride = blocker
            val content = "k".repeat(5_000)
            val entry = ToolResultEntry(toolCallId = "call_5", toolName = "search", result = content)

            val guarded = runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 1_000) }

            assertTrue(guarded.truncated)
            assertTrue(guarded.result.contains("[output truncated:"), "must fall back to truncation")
            assertTrue(guarded.result.length <= 1_000)
        }

        @Test
        fun `returns empty result for non-positive limits`() {
            val entry = ToolResultEntry(toolCallId = "call_6", toolName = "search", result = "x".repeat(100))
            val guarded = runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 0) }
            assertTrue(guarded.truncated)
            assertEquals("", guarded.result)
        }

        @Test
        fun `single-line oversized text shows 1 line and a char-budget sample without the full content`(@TempDir tempDir: Path) {
            ToolResultGuard.spillDirOverride = tempDir
            val content = "{\"symbol\":\"688012.SH\",\"close\":1.23},".repeat(20_000)
            val entry = ToolResultEntry(toolCallId = "call_7", toolName = "market_data", result = content)

            val guarded = runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 10_000) }

            assertTrue(guarded.truncated)
            assertTrue(guarded.result.contains("(1 lines)"), "single-line payload must report 1 line")
            assertTrue(guarded.result.length < content.length / 100, "notice must stay tiny relative to the original")
        }

        @Test
        fun `multi-line sample is cut at line boundaries within the char budget`(@TempDir tempDir: Path) {
            ToolResultGuard.spillDirOverride = tempDir
            val content = (1..500).joinToString("\n") { "row-$it-" + "d".repeat(80) }
            val entry = ToolResultEntry(toolCallId = "call_8", toolName = "search", result = content)

            val guarded = runBlocking { ToolResultGuard.guardEntry(entry, maxChars = 5_000) }

            val headSample = guarded.result.substringAfter("--- head ---\n").substringBefore("\n--- tail ---")
            assertTrue(headSample.lineSequence().first().startsWith("row-1-"), "head sample starts at the first row")
            assertFalse(headSample.contains("row-500-"), "head sample must not leak into the tail")
            assertTrue(headSample.length <= 1_000, "head sample respects the char budget")
        }
    }
}
