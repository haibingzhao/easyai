package com.easy.easyai.autoconfigure.r2dbc

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.suspendTransaction
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.SmartLifecycle
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Probes the framework database on a fixed interval so an outage shows up in the log instead of
 * only in request timeouts, and so recovery is visible once it happens.
 *
 * A store without a pool opens a connection per transaction, so "the database is gone" and "the
 * next request hangs" are the same event for callers; this probe is the only place that reports it
 * without user traffic. The probe is bounded by its own timeout, and only state changes are logged
 * loudly (with a periodic reminder while the outage lasts) so a long outage cannot flood the log.
 */
class DatabaseHealthMonitor(
    private val databaseProvider: () -> R2dbcDatabase,
    private val interval: Duration = Duration.ofSeconds(30),
    private val timeout: Duration = Duration.ofSeconds(5),
    private val probe: suspend (R2dbcDatabase) -> Unit = ::ping
) : SmartLifecycle, DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    @Volatile
    private var healthy = true

    @Volatile
    private var lastError: String? = null

    @Volatile
    private var unhealthySince: Instant? = null

    private var consecutiveFailures = 0

    fun isHealthy(): Boolean = healthy

    fun lastError(): String? = lastError

    override fun start() {
        if (!running.compareAndSet(false, true)) return
        job = scope.launch {
            while (currentCoroutineContext().isActive) {
                delay(interval.toMillis())
                checkOnce()
            }
        }
    }

    override fun stop() {
        running.set(false)
        job?.cancel()
        job = null
    }

    override fun destroy() {
        stop()
        scope.cancel()
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    /** Runs after [R2dbcDatabaseInitializer]: the database handle must exist before probing it. */
    override fun getPhase(): Int = Int.MIN_VALUE + 1

    /** Visible for tests: one probe pass. Never throws. */
    suspend fun checkOnce() {
        try {
            withTimeout(timeout.toMillis()) { probe(databaseProvider()) }
            onReachable()
        } catch (e: TimeoutCancellationException) {
            // The probe's own bound elapsed; that is an outage signal, not a caller cancelling us.
            onUnreachable(e)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onUnreachable(e)
        }
    }

    private fun onUnreachable(e: Exception) {
        consecutiveFailures++
        lastError = e.message ?: e.javaClass.simpleName
        val firstFailure = healthy
        healthy = false
        if (unhealthySince == null) unhealthySince = Instant.now()
        when {
            firstFailure -> logger.warn(
                "Database health probe failed; treating the database as unreachable: {}", lastError, e
            )

            consecutiveFailures % RE_WARN_EVERY == 0 -> logger.warn(
                "Database still unreachable after {} consecutive probes: {}", consecutiveFailures, lastError
            )

            else -> logger.debug(
                "Database health probe failed ({} in a row): {}", consecutiveFailures, lastError
            )
        }
    }

    private fun onReachable() {
        if (healthy) {
            consecutiveFailures = 0
            return
        }
        val downMillis = unhealthySince?.let { Duration.between(it, Instant.now()).toMillis() } ?: 0
        val failures = consecutiveFailures
        healthy = true
        unhealthySince = null
        consecutiveFailures = 0
        lastError = null
        logger.info("Database reachable again after {} failed probe(s), down for {} ms", failures, downMillis)
    }

    companion object {
        private const val RE_WARN_EVERY = 10

        /** One round trip on a fresh connection: enough to prove the configured database answers. */
        @JvmStatic
        suspend fun ping(db: R2dbcDatabase) {
            suspendTransaction(db) { exec("SELECT 1") { it.get(0) }?.firstOrNull() }
        }
    }
}
