package com.easy.easyai.core.task

/**
 * Executes one [AsyncTaskType]. A handler restores whatever it needs from [AsyncTask.payload] (so a
 * task can be re-run after a restart) and returns a [TaskOutcome]; the runner owns persistence and
 * status transitions.
 */
interface TaskHandler {
    /** The task type this handler serves. */
    val type: AsyncTaskType

    /** Run the task. Implementations should be cancellation-cooperative. */
    suspend fun execute(task: AsyncTask): TaskOutcome
}
