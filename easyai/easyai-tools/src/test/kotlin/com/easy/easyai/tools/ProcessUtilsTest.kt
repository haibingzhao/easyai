package com.easy.easyai.tools

import com.easy.easyai.core.model.ToolResultContent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Regression tests for [executeProcess] timeout behavior.
 *
 * Historical bug: the process stdout was drained with a blocking readLine() INSIDE
 * withTimeout { }. readLine() is blocking Java I/O, not a coroutine suspension point,
 * so the timeout could never fire while a process produced no output and never exited.
 * The coroutine (and the whole tool call) hung forever. These tests pin down the fix:
 * the timeout must actually terminate a hanging process and return promptly.
 */
class ProcessUtilsTest {

    @Nested
    inner class `Timeout on hanging process` {

        @Test
        fun `returns promptly with timedOut=true when process produces no output and never exits`() = runBlocking {
            // `sleep` writes nothing to stdout, so the reader blocks in readLine().
            // Before the fix this call hung for the full sleep duration (or forever);
            // after the fix it must return as soon as the timeout elapses.
            val started = System.currentTimeMillis()
            val result = executeProcess(
                command = listOf("/bin/sh", "-c", "sleep 30"),
                timeout = 500.milliseconds
            )
            val elapsed = System.currentTimeMillis() - started

            assertTrue(result.timedOut, "expected the hanging process to be marked as timed out")
            assertNull(result.exitCode, "timed-out process should have no exit code")
            // Generous bound to stay CI-friendly, but far below the 30s sleep so a
            // regression (timeout never firing) fails loudly instead of hanging.
            assertTrue(elapsed < 10_000, "timeout did not fire promptly, took ${elapsed}ms")
        }

        @Test
        fun `destroys process so it does not keep running after timeout`() = runBlocking {
            // Use a marker env-var-free approach: run a long sleep and verify the call
            // returns quickly, which implies the process tree was destroyed (otherwise
            // the reader would stay blocked and the call would not return).
            val started = System.currentTimeMillis()
            val result = executeProcess(
                command = listOf("/bin/sh", "-c", "sleep 60"),
                timeout = 300.milliseconds
            )
            val elapsed = System.currentTimeMillis() - started

            assertTrue(result.timedOut)
            assertTrue(elapsed < 10_000, "process was not destroyed on timeout, took ${elapsed}ms")
        }
    }

    @Nested
    inner class `Normal execution` {

        @Test
        fun `captures output and zero exit code`() = runBlocking {
            val result = executeProcess(
                command = listOf("/bin/sh", "-c", "echo hello"),
                timeout = 5.seconds
            )

            assertFalse(result.timedOut)
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("hello"))
        }

        @Test
        fun `captures non-zero exit code`() = runBlocking {
            val result = executeProcess(
                command = listOf("/bin/sh", "-c", "exit 3"),
                timeout = 5.seconds
            )

            assertFalse(result.timedOut)
            assertEquals(3, result.exitCode)
        }

        @Test
        fun `still returns output produced before timeout`() = runBlocking {
            // Process prints a line then hangs: the partial output must be preserved
            // even though the process is ultimately killed by the timeout.
            val result = executeProcess(
                command = listOf("/bin/sh", "-c", "echo partial; sleep 30"),
                timeout = 500.milliseconds
            )

            assertTrue(result.timedOut)
            assertTrue(result.output.contains("partial"), "partial output before timeout should be retained")
        }
    }

    @Nested
    inner class `Idle timeout resets on output` {

        @Test
        fun `process producing continuous output is NOT killed by idle timeout`() = runBlocking {
            // Outputs a line every 200ms for ~2s total; idle timeout is 800ms.
            // Because output keeps flowing (interval < 800ms), the process must NOT be killed.
            val result = executeProcess(
                command = listOf("/bin/sh", "-c",
                    "for i in 1 2 3 4 5 6 7 8 9 10; do echo tick\$i; sleep 0.2; done"),
                timeout = 800.milliseconds
            )

            assertFalse(result.timedOut, "actively producing process should not be killed")
            assertEquals(0, result.exitCode)
            assertTrue(result.output.contains("tick10"), "all output lines should be captured")
        }

        @Test
        fun `process killed after output stops and idle timeout elapses`() = runBlocking {
            // Outputs one line then goes silent for 30s; idle timeout 500ms.
            // The timer resets on the first line, then fires 500ms after the last output.
            val started = System.currentTimeMillis()
            val result = executeProcess(
                command = listOf("/bin/sh", "-c", "echo start; sleep 30"),
                timeout = 500.milliseconds
            )
            val elapsed = System.currentTimeMillis() - started

            assertTrue(result.timedOut, "process should be killed after going idle")
            assertTrue(result.output.contains("start"), "output before stall should be retained")
            assertTrue(elapsed < 10_000, "idle timeout did not fire promptly, took ${elapsed}ms")
        }
    }

    @Nested
    inner class `executeProcessWithTimeout error propagation` {

        @Test
        fun `timeout sets top-level isError and includes timeout message in output`() = runBlocking {
            val toolResult = executeProcessWithTimeout(
                command = listOf("/bin/sh", "-c", "echo before_hang; sleep 30"),
                toolCallId = "tc-test",
                toolName = "bash",
                timeout = 500.milliseconds,
                onUpdate = {}
            )

            // Top-level isError must be true so ToolExecutionEngine propagates it correctly
            assertTrue(toolResult.isError, "top-level ToolResult.isError must be true on timeout")

            val content = toolResult.content.filterIsInstance<ToolResultContent>().first()
            assertTrue(content.isError, "ToolResultContent.isError must be true on timeout")
            assertNull(content.exitCode, "timed-out process should have no exit code")
            // Output must contain an explicit stall error message
            assertTrue(content.output.contains("stalled"), "output must contain stall error message")
            // Partial output before hang is still preserved
            assertTrue(content.output.contains("before_hang"), "partial output should be retained")
        }

        @Test
        fun `non-zero exit code sets isError via includeExitCode`() = runBlocking {
            val toolResult = executeProcessWithTimeout(
                command = listOf("/bin/sh", "-c", "echo err_msg; exit 1"),
                toolCallId = "tc-test2",
                toolName = "bash",
                timeout = 5.seconds,
                includeExitCode = true,
                onUpdate = {}
            )

            assertTrue(toolResult.isError, "non-zero exit code with includeExitCode should set isError")
            val content = toolResult.content.filterIsInstance<ToolResultContent>().first()
            assertEquals(1, content.exitCode)
            assertTrue(content.output.contains("err_msg"))
        }
    }
}
