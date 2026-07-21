package com.easy.easyai.web.service

import com.easy.easyai.core.agent.WaitForUserListener
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import org.slf4j.LoggerFactory

/**
 * Pauses the goal timer when the agent loop pauses waiting for user input
 * (permission request or ask_question).
 *
 * Registered as a Spring Bean and injected into [com.easy.easyai.core.agent.DefaultAgentService]
 * so the core agent loop can notify goal pause tracking without knowing about goals directly.
 */
class GoalPauseListener(
    private val goalStore: GoalStore,
    private val goalStatusNotifier: GoalStatusNotifier?
) : WaitForUserListener {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun onWaitForUser(sessionId: String, userId: String, reason: String) {
        try {
            val goal = goalStore.getGoal(sessionId, userId)
            if (goal != null && goal.isActive && goal.pausedAt == null) {
                val paused = goal.pauseTimer()
                goalStore.saveGoal(paused, userId)
                goalStatusNotifier?.notifyGoalChanged(paused)
                logger.info("Goal timer paused for session {}", sessionId)
            }
        } catch (e: Exception) {
            logger.warn("Failed to pause goal timer for session {}: {} \u2014 goal timer will continue running, timeout may be inaccurate", sessionId, e.message)
        }
    }
}
