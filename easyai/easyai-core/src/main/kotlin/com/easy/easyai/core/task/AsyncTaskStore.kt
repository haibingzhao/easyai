package com.easy.easyai.core.task

/**
 * Persistence for [AsyncTask] rows. The table is the single source of truth for task state: the
 * runner writes every transition here, and both the global Tasks page and the Session Summary read
 * back from it (via REST), so a task outlives the SSE stream and the process that started it.
 *
 * Ownership is `user_id` strict (no `system` fold-in): a caller sees only its own tasks. Methods
 * that mutate by id are user-unscoped internally — the controller verifies ownership via [findById]
 * before delegating — while list/delete-by-user methods scope in SQL. [markRunning] additionally
 * requires this application's host ownership.
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
     * Atomically claim this host's PENDING row as RUNNING, stamping `started_at`.
     * Returns the authoritative running task only if the update won; otherwise null, including
     * when the row is already RUNNING, belongs to another host, has no owner, or does not exist.
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
     * Bulk manual cleanup of one application's tasks for a user, optionally only those in [status].
     * Returns rows removed.
     *
     * Scoped by [ownerApp] as well as user: rows another application is running have a live worker
     * that deleting them would strand (its terminal write then matches no row), so a host may only
     * clear its own.
     */
    suspend fun deleteByUser(userId: String, ownerApp: String, status: AsyncTaskStatus? = null): Int

    /**
     * Every task **this application** left non-terminal (PENDING or RUNNING) when its process went
     * away — called once at startup. SCHEDULE PENDING rows are dispatched after the other orphans
     * have been parked as INTERRUPTED; RUNNING rows are never automatically replayed.
     *
     * Rows another application on the same database owns are excluded: their workers may well be
     * alive. Rows with no owner at all are excluded too — nothing can tell whose they were, so
     * parking them would guess, and leaving them is reported instead.
     */
    suspend fun findOrphans(): List<AsyncTask>
}
