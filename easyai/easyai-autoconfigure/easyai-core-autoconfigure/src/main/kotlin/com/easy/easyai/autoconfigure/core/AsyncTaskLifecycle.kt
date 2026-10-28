package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.host.HostStartupGate
import com.easy.easyai.core.task.AsyncTaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * Owns the process-level lifecycle of [AsyncTaskRunner]: on startup it parks tasks a previous
 * process left RUNNING as INTERRUPTED (their workers died with that process), and on shutdown it
 * stops the runner's scope. The runner itself is session-independent, so nothing here is tied to a
 * chat session — mirroring [com.easy.easyai.autoconfigure.web.TempWorkspaceSweepRunner].
 *
 * On a shared database [AsyncTaskRunner.reconcileOrphans] only parks this application's rows, and
 * rows written before ownership existed are invisible until the startup adopter claims them. [gate]
 * is that adopter's completion signal: the sweep waits for it, otherwise a first boot after the
 * upgrade scans while the legacy rows still look unclaimed and leaves them RUNNING until a restart.
 */
class AsyncTaskLifecycle(
    private val runner: AsyncTaskRunner,
    private val gate: HostStartupGate?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            try {
                if (gate != null) {
                    val opened = withTimeoutOrNull(GATE_TIMEOUT_MS) { gate.awaitReady() }
                    if (opened == null) {
                        logger.warn(
                            "Legacy ownership resolution did not finish within {}ms; the orphan sweep " +
                                "runs without it and unclaimed rows stay inert until assigned",
                            GATE_TIMEOUT_MS
                        )
                    }
                }
                runner.reconcileOrphans()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Async task orphan reconciliation failed: {}", e.message)
            }
        }
    }

    override fun destroy() {
        runner.shutdown()
        scope.coroutineContext[Job]?.cancel(CancellationException("application context is closing"))
    }

    private companion object {
        /** Generous: adoption itself budgets 30s for database initialization plus its three updates. */
        const val GATE_TIMEOUT_MS = 60_000L
    }
}
