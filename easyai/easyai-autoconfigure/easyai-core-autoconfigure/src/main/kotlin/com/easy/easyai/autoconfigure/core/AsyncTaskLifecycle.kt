package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.task.AsyncTaskRunner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * On a shared database [AsyncTaskRunner.reconcileOrphans] only parks tasks belonging to this
 * application's accounts, so another process's live workers are never interrupted.
 */
class AsyncTaskLifecycle(
    private val runner: AsyncTaskRunner,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            try {
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
}
