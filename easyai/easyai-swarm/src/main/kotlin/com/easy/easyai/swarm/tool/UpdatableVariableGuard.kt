package com.easy.easyai.swarm.tool

/**
 * Permission guard for runtime-updatable swarm variables.
 *
 * Validates whether a given task is allowed to update a specific variable
 * based on two layers of control:
 * 1. Global declaration: variable must be marked as [com.easy.easyai.swarm.model.SwarmVariable.updatable] in the preset
 * 2. Task-level authorization: variable must be in the task's [com.easy.easyai.swarm.model.SwarmTask.updatableVariables] list
 *
 * Created in [com.easy.easyai.swarm.runtime.SwarmRuntime.execute] and passed to [UpdateVariableTool].
 */
class UpdatableVariableGuard(
    /** taskId → set of variable names this task is allowed to update. */
    private val taskPermissions: Map<String, Set<String>>,
    /** Globally declared updatable variable names. */
    private val updatableVarNames: Set<String>
) {
    /**
     * Check if [taskId] is allowed to update [varName].
     * @return null if allowed, error message string if denied.
     */
    fun check(taskId: String, varName: String): String? {
        if (varName !in updatableVarNames) {
            return "Variable '$varName' is not declared as updatable in the workflow variables."
        }
        val allowed = taskPermissions[taskId]
        if (allowed == null || varName !in allowed) {
            return "Variable '$varName' is not in the updatable list for task '$taskId'. " +
                "Allowed: ${allowed?.joinToString() ?: "none"}"
        }
        return null // allowed
    }
}
