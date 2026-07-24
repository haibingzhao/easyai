package com.easy.easyai.swarm.runtime

import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-level registry mapping pending team consultations to their CompletableDeferred.
 *
 * TeamTaskExecutor registers a deferred when Leader issues SUSPEND_AND_CONSULT_USER;
 * the REST API completes it when the user submits an answer.
 *
 * Key format: "$runId:$taskId:$memberId"
 */
class TeamConsultationRegistry {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<String>>()

    fun key(runId: String, taskId: String, memberId: String) = "$runId:$taskId:$memberId"

    /**
     * Register a pending consultation. Called by TeamTaskExecutor when suspending a member for user input.
     */
    fun register(runId: String, taskId: String, memberId: String, deferred: CompletableDeferred<String>) {
        val k = key(runId, taskId, memberId)
        pending[k] = deferred
        logger.debug("Registered consultation: {}", k)
    }

    /**
     * Remove a consultation entry. Called after answer/timeout/reject.
     */
    fun remove(runId: String, taskId: String, memberId: String) {
        val k = key(runId, taskId, memberId)
        pending.remove(k)
        logger.debug("Removed consultation: {}", k)
    }

    /**
     * Complete a pending consultation with the user's answer.
     * @return true if the consultation was found and completed, false if not found (already answered/expired).
     */
    fun answer(runId: String, taskId: String, memberId: String, answer: String): Boolean {
        val k = key(runId, taskId, memberId)
        val deferred = pending.remove(k) ?: return false
        deferred.complete(answer)
        logger.info("Consultation answered: {}", k)
        return true
    }

    /**
     * Reject a pending consultation (user chose to skip).
     * Completes the deferred with a special marker that TeamTaskExecutor interprets as escalation.
     * @return true if the consultation was found and rejected, false if not found.
     */
    fun reject(runId: String, taskId: String, memberId: String): Boolean {
        val k = key(runId, taskId, memberId)
        val deferred = pending.remove(k) ?: return false
        deferred.complete(REJECT_MARKER)
        logger.info("Consultation rejected: {}", k)
        return true
    }

    /**
     * Check if a consultation is pending.
     */
    fun isPending(runId: String, taskId: String, memberId: String): Boolean {
        return pending.containsKey(key(runId, taskId, memberId))
    }

    companion object {
        /** Special marker indicating the user rejected/skipped the consultation. */
        const val REJECT_MARKER = "\u0000__CONSULTATION_REJECTED__\u0000"
    }
}
