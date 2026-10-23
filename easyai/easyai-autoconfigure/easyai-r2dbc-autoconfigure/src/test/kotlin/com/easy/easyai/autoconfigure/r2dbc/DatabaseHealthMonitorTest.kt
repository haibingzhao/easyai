package com.easy.easyai.autoconfigure.r2dbc

import io.mockk.mockk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The probe is the only signal an operator gets when the database disappears between requests, so
 * its state transitions (healthy → unhealthy → healthy) and its timeout handling are the contract.
 */
class DatabaseHealthMonitorTest {

    private fun monitor(
        timeout: Duration = Duration.ofSeconds(5),
        probe: suspend (R2dbcDatabase) -> Unit
    ) = DatabaseHealthMonitor(
        databaseProvider = { mockk(relaxed = true) },
        interval = Duration.ofSeconds(30),
        timeout = timeout,
        probe = probe
    )

    @Nested
    inner class `state transitions` {

        @Test
        fun `a reachable database stays healthy`() = runBlocking {
            val monitor = monitor { }

            monitor.checkOnce()

            assertTrue(monitor.isHealthy())
            assertNull(monitor.lastError())
        }

        @Test
        fun `an unreachable database records the driver error`() = runBlocking {
            val monitor = monitor { throw IllegalStateException("Connection refused") }

            monitor.checkOnce()

            assertFalse(monitor.isHealthy())
            assertEquals("Connection refused", monitor.lastError())
        }

        @Test
        fun `recovery clears the recorded error`() = runBlocking {
            var failing = true
            val monitor = monitor { if (failing) throw IllegalStateException("database does not exist") }

            monitor.checkOnce()
            failing = false
            monitor.checkOnce()

            assertTrue(monitor.isHealthy())
            assertNull(monitor.lastError())
        }
    }

    @Nested
    inner class `bounded probes` {

        @Test
        fun `a probe that outlives its timeout counts as a failure`() = runBlocking {
            val monitor = monitor(timeout = Duration.ofMillis(50)) { delay(5_000) }

            monitor.checkOnce()

            assertFalse(monitor.isHealthy())
        }

        @Test
        fun `caller cancellation is not mistaken for an outage`() = runBlocking {
            val monitor = monitor { throw CancellationException("stopped") }

            assertFailsWith<CancellationException> { monitor.checkOnce() }
            assertTrue(monitor.isHealthy())
        }
    }
}
