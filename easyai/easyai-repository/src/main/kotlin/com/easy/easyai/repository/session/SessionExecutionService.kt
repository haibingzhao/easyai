package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.ChatSession
import kotlinx.coroutines.*
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.context.ApplicationEvent
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.ApplicationEventPublisherAware
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

/**
 * Published when a session execution completes (Agent Loop finishes).
 *
 * Covers both SSE chat and programmatic invocation (e.g., TradingAiService) paths;
 * compaction (endReason == null) does NOT publish this event.
 *
 * Listeners should not block the publishing thread — launch async processing if needed.
 *
 * @param sessionId the completed session ID
 * @param userId the owning user
 * @param endReason the reason for ending ("normal" / "max_iterations" / "cancelled" / "error" etc.)
 */
class SessionCompletedEvent(
    source: Any,
    val sessionId: String,
    val userId: String,
    val endReason: String?,
) : ApplicationEvent(source)

/**
 * Handle representing an active session execution.
 *
 * Uses identity equality (not data class) so that [ConcurrentHashMap.remove] with
 * the same key but a different handle instance acts as a single-writer guard:
 * only the currently registered handle can trigger DB state transitions on end.
 */
class ExecutionHandle internal constructor(
    val sessionId: String,
    val userId: String,
    /** The running [ChatSession], or null when the execution has no agent (compaction). */
    val session: ChatSession?,
    /** Assigned right after the atomic registration in [SessionExecutionService.beginExecutionIfIdle]. */
    @Volatile internal var beginJob: Job?,
    /** Abort hook for executions with no [session] to abort, e.g. cancelling the compaction job. */
    internal val onCancel: (() -> Unit)?
)

/**
 * Outcome of [SessionExecutionService.cancelExecution].
 *
 * @param aborted true only when this instance stopped a live run of its own.
 * @param rowWasStreaming the session row read `streaming` immediately before this cancel downgraded
 *   it. Captured because the call itself erases both proofs: it removes the local handle and writes
 *   the row to `active`, so after it returns neither this instance's registry nor the row can say
 *   whether a run was live. With `aborted == false` it is the difference between "there was nothing
 *   running" and "something was running that this instance could not stop".
 */
data class CancelVerdict(
    val aborted: Boolean,
    val rowWasStreaming: Boolean,
)

/**
 * Shared session execution lifecycle manager.
 *
 * Maintains an in-memory registry of locally executing sessions and synchronizes
 * their status to the database. Used by both the SSE web layer ([ChatStreamService])
 * and programmatic invocations (e.g., TradingAiService) to ensure consistent
 * streaming/active status and endReason persistence.
 *
 * The in-memory registry enables the `streaming-status` endpoint to report
 * `local=true`, which the frontend requires to sustain polling during execution.
 */
class SessionExecutionService(
    private val sessionStore: AsyncSessionStore?,
    dispatcher: CoroutineDispatcher = Dispatchers.Default
) : DisposableBean, ApplicationEventPublisherAware {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val executions = ConcurrentHashMap<String, ExecutionHandle>()
    /**
     * Serialises registration publication, idle admission and cancel-side removal.
     *
     * The handle's [ExecutionHandle.beginJob] must be visible before the handle is visible to a
     * concurrent cancel: otherwise cancel can remove the handle, read `beginJob == null`, skip the
     * join and leave the row at `streaming`. `beginExecution` takes the same lock so an idle
     * admission's check-and-set cannot interleave with an overwrite-by-design registration.
     * [endExecution]'s conditional remove stays outside the lock: identity comparison already makes
     * it a single-writer guard, and it never publishes a handle.
     */
    private val admissionLock = Any()
    private val statusScope = CoroutineScope(SupervisorJob() + dispatcher)
    private var eventPublisher: ApplicationEventPublisher? = null

    override fun setApplicationEventPublisher(publisher: ApplicationEventPublisher) {
        this.eventPublisher = publisher
    }

    /**
     * Register a new execution and fire-and-forget mark the session as streaming in DB.
     *
     * Uses overwrite semantics: if a prior execution exists for the same sessionId
     * (e.g., SSE reconnect), the old handle is replaced. The old handle's [endExecution]
     * will become a no-op due to the conditional remove guard.
     *
     * @param sessionId The session ID
     * @param userId The owning user ID
     * @param session The running ChatSession (null for compaction)
     * @param resetEndReason Whether to clear stale endReason (false for compaction)
     * @param onCancel Abort hook for executions with no [session]; without either, [cancelExecution]
     *   has nothing to stop and cannot report a confirmed cancel
     * @return A handle to pass to [endExecution] when the execution completes
     */
    fun beginExecution(
        sessionId: String,
        userId: String,
        session: ChatSession? = null,
        resetEndReason: Boolean = true,
        onCancel: (() -> Unit)? = null
    ): ExecutionHandle {
        val handle = ExecutionHandle(
            sessionId, userId, session, markStreaming(sessionId, userId, resetEndReason), onCancel,
        )
        val previous = synchronized(admissionLock) { executions.put(sessionId, handle) }
        if (previous != null) {
            logger.warn("Overwriting execution handle for session {} (SSE reconnect or concurrent invocation)", sessionId)
        }
        return handle
    }

    /**
     * Register an execution only when this instance is idle for [sessionId].
     *
     * [beginExecution] overwrites by design (an SSE reconnect), but a caller that must not disturb
     * an already-running chat or compaction cannot get that from a separate
     * [isLocallyExecuting] pre-check followed by [beginExecution]: another run can register in
     * between. The check-and-register below runs under [admissionLock], which [beginExecution] and
     * [cancelExecution] also take, so at most one caller wins; `null` means "busy".
     */
    fun beginExecutionIfIdle(
        sessionId: String,
        userId: String,
        session: ChatSession? = null,
        resetEndReason: Boolean = true,
        onCancel: (() -> Unit)? = null
    ): ExecutionHandle? {
        val handle = ExecutionHandle(sessionId, userId, session, null, onCancel)
        synchronized(admissionLock) {
            if (executions.containsKey(sessionId)) return null
            // Assign before publishing: a cancel that removes the handle must see the job it has
            // to join, otherwise it can downgrade the row before markStreaming writes `streaming`.
            handle.beginJob = markStreaming(sessionId, userId, resetEndReason)
            executions[sessionId] = handle
        }
        return handle
    }

    private fun markStreaming(sessionId: String, userId: String, resetEndReason: Boolean): Job? =
        sessionStore?.let { store ->
            statusScope.launch {
                try {
                    store.updateStatus(sessionId, "streaming", userId)
                    if (resetEndReason) {
                        store.saveEndReason(sessionId, "normal", userId)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to set streaming status for session {}: {}", sessionId, e.message)
                }
            }
        }

    /**
     * End an execution and transition DB state back to active.
     *
     * Uses conditional remove (identity comparison): only succeeds if [handle] is still
     * the registered handle for its sessionId. If [cancelExecution] already removed it,
     * this method is a no-op — preventing the execution's endReason from overwriting "cancelled".
     *
     * DB writes are performed in a single coroutine with strict ordering:
     * join beginJob → saveEndReason → updateStatus("active", expected="streaming").
     *
     * @param handle The handle returned by [beginExecution]
     * @param endReason The reason for ending (null to skip saveEndReason, e.g., compaction)
     */
    fun endExecution(handle: ExecutionHandle, endReason: String? = null) {
        // Conditional remove: only proceed if this handle is still the registered one
        if (!executions.remove(handle.sessionId, handle)) {
            logger.debug("endExecution no-op for session {} (handle already removed by cancel or reconnect)",
                handle.sessionId)
            return
        }

        statusScope.launch {
            sessionStore?.let { store ->
                // Wait for beginJob to complete to avoid race:
                // markStreaming's saveEndReason("normal") could overwrite the actual endReason
                try {
                    handle.beginJob?.join()
                } catch (e: Exception) {
                    logger.warn("Begin job failed for session {}: {}", handle.sessionId, e.message)
                }
                if (endReason != null) {
                    try {
                        store.saveEndReason(handle.sessionId, endReason, handle.userId)
                    } catch (e: Exception) {
                        logger.warn("Failed to save endReason for session {}: {}", handle.sessionId, e.message)
                    }
                }
                try {
                    store.updateStatus(handle.sessionId, "active", handle.userId, expectedStatus = "streaming")
                } catch (e: Exception) {
                    logger.warn("Failed to set active status for session {}: {}", handle.sessionId, e.message)
                }
            }

            // Publish completion event after DB state is settled; compaction (endReason == null) skips
            if (endReason != null) {
                try {
                    eventPublisher?.publishEvent(
                        SessionCompletedEvent(
                            this@SessionExecutionService,
                            handle.sessionId,
                            handle.userId,
                            endReason,
                        )
                    )
                } catch (e: Exception) {
                    logger.warn("Failed to publish SessionCompletedEvent for session {}: {}",
                        handle.sessionId, e.message)
                }
            }
        }
    }

    /**
     * Cancel an active execution.
     *
     * Synchronous (suspend) to guarantee that streaming-status is immediately correct
     * after cancellation. Removes the handle first so that the execution flow's
     * [endExecution] becomes a no-op and cannot overwrite "cancelled" endReason.
     *
     * @param sessionId The session ID to cancel
     * @param userId The owning user ID
     * @return [CancelVerdict] — `aborted` reports whether this instance stopped a live run, and
     *   [CancelVerdict.rowWasStreaming] carries the pre-downgrade row state as the only evidence
     *   left for callers, because the DB downgrade below runs either way. A false `aborted` does not
     *   mean the cancel was ignored.
     */
    suspend fun cancelExecution(sessionId: String, userId: String): CancelVerdict {
        // Same critical section as admission: either this removes a fully-published handle (with
        // beginJob assigned) or the admission has not published one yet and will see this removal.
        val handle = synchronized(admissionLock) { executions.remove(sessionId) }
        val abortable = handle != null && (handle.session != null || handle.onCancel != null)
        when {
            handle == null ->
                // Was silent before: the caller had no way to tell "stopped a run" from "there was
                // nothing here to stop" (already finished, already cancelled, or executing on
                // another instance), so an ineffective cancel looked exactly like a successful one.
                logger.warn("Cancel for session {} found no local run to stop (finished, already cancelled, or another instance)", sessionId)
            !abortable ->
                logger.warn("Cancel for session {} matched a registered run with no abort path, reporting it as unconfirmed", sessionId)
        }
        handle?.session?.abort()
        handle?.onCancel?.invoke()

        // Wait for beginJob to ensure DB is in "streaming" before downgrading.
        // Without this, cancel could race with the async markStreaming:
        // cancel's updateStatus("active", expected="streaming") would be a no-op
        // if beginJob hasn't executed yet, leaving the session stuck in "streaming".
        try {
            handle?.beginJob?.join()
        } catch (e: Exception) {
            logger.warn("Begin job failed during cancel for session {}: {}", sessionId, e.message)
        }

        // Read after the join (the row may not say `streaming` until then) and before the downgrade
        // below (after it, nothing can). Only when nothing was aborted here: a confirmed stop already
        // answers for itself, so the confirmed path stays free of an extra round trip. A failed read
        // degrades to `false`, which is the pre-verdict behaviour (report nothing to the user).
        val rowWasStreaming = !abortable && runCatching {
            sessionStore?.findStatus(sessionId, userId) == "streaming"
        }.getOrElse {
            logger.warn("Failed to read status before cancel for session {}: {}", sessionId, it.message)
            false
        }

        sessionStore?.let { store ->
            try {
                store.updateStatus(sessionId, "active", userId, expectedStatus = "streaming")
                store.saveEndReason(sessionId, "cancelled", userId)
            } catch (e: Exception) {
                logger.warn("Failed to update status after cancel for session {}: {}", sessionId, e.message)
            }
        }
        return CancelVerdict(aborted = abortable, rowWasStreaming = rowWasStreaming)
    }

    /**
     * Check if a session is currently executing on this server instance.
     * Used by the streaming-status endpoint to report `local=true`.
     */
    fun isLocallyExecuting(sessionId: String): Boolean = executions.containsKey(sessionId)

    /**
     * Get the active [ChatSession] for a session, if executing locally.
     * Used by cancel/queue operations that need the running instance.
     */
    fun getActiveSession(sessionId: String): ChatSession? = executions[sessionId]?.session

    /**
     * Return the number of sessions currently executing on this server.
     */
    fun getActiveSessionCount(): Int = executions.size

    /**
     * On shutdown: mark all locally-held sessions as active so that
     * rolling restart / crash does not leave stale "streaming" rows in DB.
     *
     * NOTE: In-flight endExecution coroutines are cancelled by [statusScope.cancel()];
     * their pending saveEndReason writes may be lost. Only the status reset is guaranteed.
     */
    override fun destroy() {
        val localSessions = executions.entries.map { it.value }
        executions.clear()
        statusScope.cancel()

        if (sessionStore == null || localSessions.isEmpty()) return

        runBlocking {
            withTimeoutOrNull(10_000.milliseconds) {
                localSessions.forEach { handle ->
                    try {
                        sessionStore.updateStatus(handle.sessionId, "active", handle.userId, expectedStatus = "streaming")
                    } catch (e: Exception) {
                        logger.warn("Failed to reset streaming status on shutdown for session {}", handle.sessionId, e)
                    }
                }
            }
        }
    }
}
