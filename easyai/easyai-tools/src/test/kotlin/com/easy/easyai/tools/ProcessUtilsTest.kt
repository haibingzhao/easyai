package com.easy.easyai.tools

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
}
