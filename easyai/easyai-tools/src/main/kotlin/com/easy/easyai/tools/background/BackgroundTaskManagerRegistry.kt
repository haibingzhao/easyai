package com.easy.easyai.tools.background

import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.event.CustomEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Spring singleton that manages per-session [BackgroundTaskManager] instances.
 * Handles lifecycle: lazy creation on first use, cleanup on session destroy or server shutdown.
 */
@Component
class BackgroundTaskManagerRegistry : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val managers = ConcurrentHashMap<String, BackgroundTaskManager>()

    /**
     * Get or create a BackgroundTaskManager for the given session.
     *
     * @param sessionId Session identifier
     * @param sessionLookup Lambda to dynamically find the ChatSession instance
     * @param isExecutingCheck Check if the session is currently executing (loop running)
     * @param eventPublisher Lambda to publish CustomEvent for SSE streaming
     * @param autoResumeTrigger Lambda to trigger auto-resume when loop is idle
     * @return The BackgroundTaskManager for this session
     */
    fun getOrCreate(
        sessionId: String,
        sessionLookup: () -> ChatSession?,
        isExecutingCheck: () -> Boolean,
        eventPublisher: (CustomEvent) -> Unit,
        autoResumeTrigger: suspend (sessionId: String, userId: String) -> Unit
    ): BackgroundTaskManager {
        return managers.getOrPut(sessionId) {
            logger.info("Creating BackgroundTaskManager for session {}", sessionId)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            BackgroundTaskManager(
                sessionId = sessionId,
                scope = scope,
                sessionLookup = sessionLookup,
                isExecutingCheck = isExecutingCheck,
                eventPublisher = eventPublisher,
                autoResumeTrigger = autoResumeTrigger
            )
        }
    }

    /**
     * Get the BackgroundTaskManager for a session, or null if not created yet.
     */
    fun get(sessionId: String): BackgroundTaskManager? = managers[sessionId]

    /**
     * Remove and cleanup the BackgroundTaskManager for a session.
     * Called when session is deleted or no longer needed.
     */
    fun remove(sessionId: String) {
        val manager = managers.remove(sessionId)
        if (manager != null) {
            logger.info("Removing BackgroundTaskManager for session {}", sessionId)
            manager.cancelAll()
        }
    }

    /**
     * Cleanup all managers on server shutdown.
     */
    override fun destroy() {
        logger.info("Shutting down BackgroundTaskManagerRegistry, cancelling all tasks")
        managers.values.forEach { it.cancelAll() }
        managers.clear()
    }
}
