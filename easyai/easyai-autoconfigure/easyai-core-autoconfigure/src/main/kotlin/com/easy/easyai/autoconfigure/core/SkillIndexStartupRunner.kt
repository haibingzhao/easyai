package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.skills.SkillRefreshService
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import kotlin.time.Duration.Companion.milliseconds

/**
 * Brings every known owner's skills up to date after startup, without ever blocking readiness, then
 * keeps the retrieval index converged with a bounded background retry pass.
 *
 * Owners come from the catalog's distinct `user_id` values plus the shared `system` layer, which is
 * swept unconditionally — a fresh machine with an empty catalog still claims hand-placed skills under
 * its owner root at startup. Never from a guess about who is logged in.
 * Each gets the same [SkillRefreshService.ensureSynced] pass a first request would trigger,
 * so a machine that was offline while a user's skills changed restores them from object storage
 * before anybody asks. The retry pass drives [SkillRefreshService.reconcilePending]: the catalog's
 * per-row `nextAttemptAt` backoff decides what is due, so failed submissions, post-submit process
 * exits and silently lost remote documents are advanced without a full rebuild. Container shutdown
 * cancels the loop and cancellation always escapes the catches untouched.
 */
class SkillIndexStartupRunner(
    private val refreshService: SkillRefreshService,
    private val catalog: AsyncSkillCatalogStore?,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            try {
                syncKnownOwners()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Skill startup sync aborted: {}", e.message)
            }
            var pending = 0
            while (isActive) {
                delay((if (pending > 0) RETRY_SWEEP_MS else IDLE_SWEEP_MS).milliseconds)
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

    private suspend fun syncKnownOwners() {
        // The shared layer always exists — even with an empty or absent catalog, its owner root
        // must be swept so directories placed on disk by hand get claimed at startup.
        val owners = (catalog?.listDistinctUserIds().orEmpty() + SkillCatalogEntry.DEFAULT_USER_ID).distinct()
        logger.info("Startup skill sync for {} owner(s)", owners.size)
        owners.forEach { refreshService.ensureSynced(it) }
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
