package com.easy.easyai.tools.calc

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.message.ToolResultGuard
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Regression tests for [ScriptCalcTool] resource limits.
 *
 * Historical bug: the timeout was implemented with kotlinx `withTimeout` around
 * the blocking `shell.evaluate(script)` call. The block contains no suspension
 * point, so cancellation is only delivered after `evaluate` returns — a runaway
 * script (e.g. `while (true)`) hung the tool forever instead of timing out.
 * The fix runs the script on a daemon worker thread with `Thread.join(timeout)`
 * plus a [ScriptLoopGuard] step budget that deterministically aborts pure-CPU
 * loops (`Thread.interrupt()` cannot stop tight loops).
 */
class ScriptCalcToolTest {

    private val tool = ScriptCalcTool(
        ToolMetadata(name = "calc", description = "test", permissionCategory = "calc")
    )

    private data class RunOutcome(val elapsed: Duration, val isError: Boolean, val output: String)

    private fun runScript(script: String): RunOutcome = runArgs(mapOf("script" to script))

    private fun runArgs(
        args: Map<String, Any?>,
        unattended: Boolean = false,
        projectPath: Path? = null
    ): RunOutcome = runBlocking {
        val started = TimeSource.Monotonic.markNow()
        val result = tool.execute(
            agentContext = AgentContext(
                agentId = "test-agent",
                projectPath = projectPath,
                unattended = unattended
            ),
            toolCallId = "tc-test",
            messageId = null,
            args = args,
            coroutineScope = this,
            onUpdate = { _: ToolUpdate -> }
        )
        val output = result.content.filterIsInstance<TextContent>().joinToString("\n") { it.text }
        RunOutcome(started.elapsedNow(), result.isError, output)
    }

    @Nested
    inner class `Step limit` {

        @Test
        fun `infinite loop is aborted by step limit, not wall timeout`() {
            val outcome = runScript("while (true) { }")

            assertTrue(outcome.isError, "runaway loop must surface as a tool error")
            assertTrue(outcome.output.contains("step limit"), "expected step-limit message, got: ${outcome.output}")
            assertTrue(
                outcome.elapsed.inWholeSeconds < 5,
                "step limit must fire well before the 10s wall timeout, took ${outcome.elapsed}"
            )
        }

        @Test
        fun `infinite loop inside closure iteration is also bounded`() {
            val outcome = runScript("(1..3).each { while (true) { } }")

            assertTrue(outcome.isError)
            assertTrue(outcome.output.contains("step limit"), "expected step-limit message, got: ${outcome.output}")
            assertTrue(outcome.elapsed.inWholeSeconds < 5)
        }

        @Test
        fun `nested infinite loops are bounded`() {
            val outcome = runScript("while (true) { while (true) { } }")

            assertTrue(outcome.isError)
            assertTrue(outcome.output.contains("step limit"))
        }

        @Test
        fun `unbounded recursion surfaces as a tool error`() {
            val outcome = runScript("def f() { f() }; f()")

            assertTrue(outcome.isError, "stack overflow must not escape as a JVM Error")
        }

        @Test
        fun `legitimate large loops stay within budget`() {
            // Long accumulator to avoid Integer overflow; still 100k closure ticks
            val outcome = runScript("(1L..100000L).sum()")

            assertFalse(outcome.isError, "100k iterations must be allowed, got: ${outcome.output}")
            assertEquals("5000050000", outcome.output)
        }

        @Test
        fun `scripts cannot tamper with the loop guard`() {
            val outcome = runScript("ScriptLoopGuard.disarm(); while (true) { }")

            assertTrue(outcome.isError)
            assertTrue(
                outcome.output.contains("Security violation"),
                "guard access must be rejected at compile time, got: ${outcome.output}"
            )
        }
    }

    @Nested
    inner class `Wall-clock timeout` {

        @Test
        fun `loop-free CPU work is stopped by the join timeout`() {
            // Parsing a million-digit BigInteger burns seconds of pure CPU inside a
            // single host call — no loop statement, so the step guard cannot see it.
            // The join(timeout) fallback must fire; a short timeout keeps the test fast.
            val script = "new BigInteger('9' * 1000000)"
            val started = TimeSource.Monotonic.markNow()
            assertThrows(ScriptCalcTool.ScriptTimeoutException::class.java) {
                tool.evaluateWithTimeout(GroovySandbox.createSecureShell(), script, 200.milliseconds)
            }
            assertTrue(
                started.elapsedNow().inWholeSeconds < 5,
                "timeout must fire at the configured deadline, not after the work finishes"
            )
        }

        @Test
        fun `fast script still completes normally`() {
            val outcome = runScript("1 + 1")

            assertFalse(outcome.isError, "simple script must not error")
            assertEquals("2", outcome.output)
            assertTrue(outcome.elapsed.inWholeSeconds < 10, "simple script must finish quickly")
        }
    }

    @Nested
    inner class `File analysis` {

        @Test
        fun `file lines are injected as __data__`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("data.csv")
            Files.writeString(file, "name,value\na,1\nb,2")

            val outcome = runArgs(mapOf("script" to "__data__.drop(1).size()", "filePath" to file.toString()))

            assertFalse(outcome.isError, outcome.output)
            assertEquals("2", outcome.output)
        }

        @Test
        fun `spilled JSON tool output is analyzed without a shell`() {
            val dir = ToolResultGuard.spillDirectory()
            Files.createDirectories(dir)
            val file = Files.createTempFile(dir, "calc-spill-", ".txt")
            try {
                Files.writeString(file, """[{"close":1},{"close":3}]""")

                val outcome = runArgs(
                    mapOf(
                        "script" to """def rows = new groovy.json.JsonSlurper().parseText(__data__.join('\n')); rows.sum { it.close }""",
                        "filePath" to file.toString()
                    ),
                    unattended = true
                )

                assertFalse(outcome.isError, outcome.output)
                assertEquals("4", outcome.output)
            } finally {
                Files.deleteIfExists(file)
            }
        }

        @Test
        fun `unattended run rejects a file outside the spill dir and project`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("data.csv")
            Files.writeString(file, "a\n1")

            val outcome = runArgs(
                mapOf("script" to "__data__.size()", "filePath" to file.toString()),
                unattended = true
            )

            assertTrue(outcome.isError)
            assertTrue(outcome.output.contains("an unattended run may only read"), outcome.output)
        }

        @Test
        fun `unattended run accepts a file inside the project`(@TempDir tempDir: Path) {
            val file = tempDir.resolve("data.csv")
            Files.writeString(file, "a\n1")

            val outcome = runArgs(
                mapOf("script" to "__data__.size()", "filePath" to file.toString()),
                unattended = true,
                projectPath = tempDir
            )

            assertFalse(outcome.isError, outcome.output)
            assertEquals("2", outcome.output)
        }

        @Test
        fun `builder declares calc read-only so unattended runs receive it`() {
            assertTrue(ToolCapability.READ_ONLY in ScriptCalcToolBuilder().metadata.capabilities)
        }
    }
}
