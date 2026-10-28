package com.easy.easyai.core.task

/**
 * Persistence for [AsyncTask] rows. The table is the single source of truth for task state: the
 * runner writes every transition here, and both the global Tasks page and the Session Summary read
 * back from it (via REST), so a task outlives the SSE stream and the process that started it.
 *
 * Ownership is `user_id` strict (no `system` fold-in): a caller sees only its own tasks. Methods
 * that mutate by id are user-unscoped internally — the controller verifies ownership via [findById]
 * before delegating — while list/delete-by-user methods scope in SQL. Cross-user background passes
 * ([findOrphans]) are scoped to accounts of this application through `app_user.owner_app`.
 *
 * State transitions are SQL-guarded so a terminal row is never overwritten: [markRunning] only fires
 * from PENDING, and [complete]/[fail]/[markTimedOut]/[cancel]/[markInterrupted]/[updateSessionId]
 * only from a non-terminal row. A guarded-out call returns the current row unchanged, except
 * [markRunning], which returns null unless this caller actually won the claim.
 */
interface AsyncTaskStore {

    /** Insert a new task row (status PENDING unless the caller set otherwise). */
    suspend fun create(task: AsyncTask): AsyncTask

    /**
     * Atomically claim a PENDING row as RUNNING, stamping `started_at`.
     * Returns the authoritative running task only if the update won; otherwise null, including
     * when the row is already RUNNING or does not exist.
     */
    suspend fun markRunning(id: String): AsyncTask?

    /** Transition to COMPLETED with [result], stamping `completed_at`. */
    suspend fun complete(id: String, result: Map<String, Any?>?): AsyncTask?

    /** Transition to FAILED with [error], stamping `completed_at`. */
    suspend fun fail(id: String, error: String): AsyncTask?

    /** Transition to TIMED_OUT with [error], stamping `completed_at`. */
    suspend fun markTimedOut(id: String, error: String): AsyncTask?

    /** Transition to CANCELLED, stamping `completed_at`. */
    suspend fun cancel(id: String): AsyncTask?

    /** Transition to INTERRUPTED (orphan reconciliation after a restart). */
    suspend fun markInterrupted(id: String): AsyncTask?

    /**
     * Bind the task to its backing session once the handler mints one. Tasks created by the
     * controller or a scheduled planner already carry the id; this covers rows restored from an older
     * build or created without one.
     */
    suspend fun updateSessionId(id: String, sessionId: String): AsyncTask?

    /**
     * Reset a terminal task for another run in place: status → PENDING, clear `error`/`result`/
     * `completed_at`, increment `retry_count` and stamp `retried_at`. The same id is reused.
     */
    suspend fun retry(id: String): AsyncTask?

    /** Fetch one task by id regardless of owner (controllers check ownership afterwards). */
    suspend fun findById(id: String): AsyncTask?

    /** List a user's tasks, newest first, with optional type/status filters. */
    suspend fun listByUser(
        userId: String,
        type: AsyncTaskType? = null,
        status: AsyncTaskStatus? = null,
        sessionId: String? = null,
        limit: Int = 100
    ): List<AsyncTask>

    /**
     * A user's unsettled tasks (PENDING or RUNNING), newest first, with an optional type filter.
     *
     * Status is filtered where the rows live rather than by trimming [listByUser] and dropping
     * terminal rows afterwards: a newest-first cap plus in-memory filtering loses an old queued task
     * as soon as that user's history exceeds the cap — the one case duplicate suppression exists for.
     */
    suspend fun listNonTerminal(userId: String, type: AsyncTaskType? = null, limit: Int = 100): List<AsyncTask>

    /** Delete one task by id. Returns true when a row was removed. */
    suspend fun delete(id: String): Boolean

    /**
     * Bulk manual cleanup of one user's tasks, optionally only those in [status]. Returns rows
     * removed. The caller is request-scoped, so the user id already identifies one application's
     * account.
     */
    suspend fun deleteByUser(userId: String, status: AsyncTaskStatus? = null): Int

    /**
     * Every task **belonging to this application's accounts** that was left non-terminal (PENDING
     * or RUNNING) when a process went away — called once at startup. SCHEDULE PENDING rows are
     * dispatched after the other orphans have been parked as INTERRUPTED; RUNNING rows are never
     * automatically replayed.
     *
     * Tasks of another application's accounts, of `system`, or of a user that no longer exists are
     * excluded: no process is responsible for executing them.
     */
    suspend fun findOrphans(): List<AsyncTask>
}
