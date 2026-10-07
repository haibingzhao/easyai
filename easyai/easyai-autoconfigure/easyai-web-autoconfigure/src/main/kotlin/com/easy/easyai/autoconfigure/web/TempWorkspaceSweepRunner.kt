package com.easy.easyai.autoconfigure.web

import com.easy.easyai.web.service.DefaultWorkspaceService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import kotlin.time.Duration.Companion.milliseconds

/**
 * Reconciles system-managed temporary workspaces against the session table, right after startup and
 * then on a fixed interval.
 *
 * A workspace row is written before its session row exists, so a first turn that is cancelled or
 * fails leaves a workspace no user can delete from the UI. Without this pass those directories and
 * rows accumulate for the lifetime of the data directory. The sweep itself lives in
 * [DefaultWorkspaceService.sweepOrphanWorkspaces], including the grace period that keeps it away
 * from in-flight turns; this class only owns the lifecycle.
 */
class TempWorkspaceSweepRunner(
    private val workspaceService: DefaultWorkspaceService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            while (isActive) {
                try {
                    workspaceService.sweepOrphanWorkspaces()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Temporary workspace sweep failed: {}", e.message)
                }
                delay(SWEEP_INTERVAL_MS.milliseconds)
            }
        }
    }

    override fun destroy() {
        scope.coroutineContext[Job]
            ?.cancel(CancellationException("application context is closing"))
    }

    companion object {
        private const val SWEEP_INTERVAL_MS = 6 * 60 * 60 * 1000L
    }
}
