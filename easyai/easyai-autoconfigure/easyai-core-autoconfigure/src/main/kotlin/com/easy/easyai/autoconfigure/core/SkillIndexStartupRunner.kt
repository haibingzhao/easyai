package com.easy.easyai.autoconfigure.core

import com.easy.easyai.skills.SkillRefreshService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * Brings the skill index up to date after startup, without ever blocking readiness, then keeps it
 * converged with a bounded background retry pass.
 *
 * The startup pass lives in [SkillRefreshService.reconcileAllOwners] — the same code the
 * `refresh_skills` tool drives on request, so the two can never disagree about what "up to date"
 * means. The retry pass drives [SkillRefreshService.reconcilePending]: the catalog's per-row
 * `nextAttemptAt` backoff decides what is due, so failed submissions, post-submit process exits
 * and silently lost remote documents are advanced without a full rebuild. Container shutdown
 * cancels the loop and cancellation always escapes the catches untouched.
 */
class SkillIndexStartupRunner(
    private val refreshService: SkillRefreshService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            try {
                refreshService.reconcileAllOwners()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Skill index reconciliation aborted after startup: {}", e.message)
            }
            var pending = 0
            while (isActive) {
                delay(if (pending > 0) RETRY_SWEEP_MS else IDLE_SWEEP_MS)
                if (!isActive) break
                try {
                    pending = refreshService.reconcilePending().pending
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Skill index retry pass failed: {}", e.message)
                }
            }
        }
    }

    override fun destroy() {
        scope.coroutineContext[kotlinx.coroutines.Job]
            ?.cancel(CancellationException("application context is closing"))
    }

    companion object {
        private const val RETRY_SWEEP_MS = 5_000L
        private const val IDLE_SWEEP_MS = 60_000L
    }
}
