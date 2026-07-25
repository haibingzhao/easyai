package com.easy.easyai.swarm.store

import com.easy.easyai.core.team.TeamMemberExecution
import com.easy.easyai.swarm.model.*

/**
 * Persistence interface for swarm runs.
 *
 * Implementations may use R2DBC (async), in-memory storage, or file-based storage.
 * The R2DBC implementation lives in the `easyai-repository` module.
 */
interface SwarmRunStore {

    /**
     * Save a new swarm run.
     * @param userId Owner user ID for data isolation.
     */
    suspend fun saveRun(run: SwarmRun, userId: String)

    /**
     * Update an existing swarm run (status, tokens, timestamps).
     * @param userId Owner user ID for row-level filtering.
     */
    suspend fun updateRun(run: SwarmRun, userId: String)

    /**
     * Get a swarm run by ID.
     * Only returns runs owned by [userId] or the system user.
     */
    suspend fun getRun(runId: String, userId: String): SwarmRun?

    /**
     * List swarm runs (most recent first).
     * Only returns runs owned by [userId] or the system user.
     */
    suspend fun listRuns(limit: Int = 20, offset: Int = 0, userId: String): List<SwarmRun>

    /**
     * Save task results for a run.
     */
    suspend fun saveTasks(runId: String, tasks: List<SwarmTask>)

    /**
     * Get all tasks for a run.
     */
    suspend fun getTasks(runId: String): List<SwarmTask>

    /**
     * Save deliberation history entries.
     */
    suspend fun saveDeliberationHistory(runId: String, taskId: String, entries: List<DeliberationEntry>)

    /**
     * Get deliberation history for a specific task in a run.
     */
    suspend fun getDeliberationHistory(runId: String, taskId: String): List<DeliberationEntry>

    /**
     * Save deliberation verdict (Judge's prompt and response).
     */
    suspend fun saveDeliberationVerdict(runId: String, taskId: String, verdictPrompt: String, verdictResponse: String)

    /**
     * Get deliberation verdict for a specific task in a run.
     * Returns pair of (verdictPrompt, verdictResponse) or null if not found.
     */
    suspend fun getDeliberationVerdict(runId: String, taskId: String): Pair<String, String>?

    /**
     * Save team escalation history entries.
     */
    suspend fun saveEscalationHistory(runId: String, taskId: String, entries: List<EscalationEntry>)

    /**
     * Get team escalation history for a specific task in a run.
     */
    suspend fun getEscalationHistory(runId: String, taskId: String): List<EscalationEntry>

    /**
     * Save team execution history: member executions and round records.
     */
    suspend fun saveTeamHistory(runId: String, taskId: String, executions: List<TeamMemberExecution>, rounds: List<TeamRoundRecord>)

    /**
     * Get team execution history for a specific task in a run.
     * Returns pair of (memberExecutions, roundRecords).
     */
    suspend fun getTeamHistory(runId: String, taskId: String): Pair<List<TeamMemberExecution>, List<TeamRoundRecord>>

    /**
     * Delete a swarm run and all associated data.
     * Only deletes if the run is owned by [userId] (strict match, no system fallback).
     */
    suspend fun deleteRun(runId: String, userId: String)

    /**
     * Save (upsert) a single task result for a run.
     * Used for real-time persistence as tasks complete or fail.
     */
    suspend fun saveTask(runId: String, task: SwarmTask)

    /**
     * List all runs matching a specific status (for recovery on restart).
     */
    suspend fun listRunsByStatus(status: SwarmRunStatus): List<SwarmRun>

    /**
     * Persist run results: update run state, save tasks, and save deliberation history.
     * Default implementation composing [updateRun], [saveTasks], and [saveDeliberationHistory].
     */
    suspend fun persistRunResults(run: SwarmRun, userId: String) {
        updateRun(run, userId)
        saveTasks(run.id, run.tasks)
        for (task in run.tasks) {
            if (task.type == TaskType.DELIBERATION && task.deliberationHistory.isNotEmpty()) {
                saveDeliberationHistory(run.id, task.id, task.deliberationHistory)
                if (task.verdictPrompt != null && task.verdictResponse != null) {
                    saveDeliberationVerdict(run.id, task.id, task.verdictPrompt!!, task.verdictResponse!!)
                }
            }
            if (task.type == TaskType.TEAM) {
                if (task.escalationHistory.isNotEmpty()) {
                    saveEscalationHistory(run.id, task.id, task.escalationHistory)
                }
                if (task.memberExecutions.isNotEmpty() || task.roundRecords.isNotEmpty()) {
                    saveTeamHistory(run.id, task.id, task.memberExecutions, task.roundRecords)
                }
            }
        }
    }
}
