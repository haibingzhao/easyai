package com.easy.easyai.autoconfigure.core

import com.easy.easyai.skills.SkillRefreshService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener

/**
 * Brings the skill index up to date after startup, without ever blocking readiness.
 *
 * The pass itself lives in [SkillRefreshService.reconcileAllOwners] — the same code the
 * `refresh_skills` tool drives on request, so the two can never disagree about what "up to date"
 * means. All that stays here is the wiring of the event to a background coroutine and the rule that
 * nothing this does may escape into application startup.
 */
class SkillIndexStartupRunner(
    private val refreshService: SkillRefreshService,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            try {
                refreshService.reconcileAllOwners()
            } catch (e: Exception) {
                logger.warn("Skill index reconciliation aborted after startup: {}", e.message)
            }
        }
    }
}
