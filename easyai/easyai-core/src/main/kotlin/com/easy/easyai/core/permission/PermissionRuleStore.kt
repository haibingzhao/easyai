package com.easy.easyai.core.permission

/**
 * Storage interface for permission rules.
 * Rules are scoped per project.
 */
interface PermissionRuleStore {

    /**
     * Load all permission rules for a project.
     * @param projectId The project ID
     * @return List of permission rules, ordered by creation time
     */
    suspend fun loadRules(projectId: String): List<PermissionRule>

    /**
     * Save all permission rules for a project (full replacement).
     * @param projectId The project ID
     * @param rules The complete list of rules to save
     */
    suspend fun saveRules(projectId: String, rules: List<PermissionRule>)

    /**
     * Add a single permission rule to a project.
     * @param projectId The project ID
     * @param rule The rule to add
     */
    suspend fun addRule(projectId: String, rule: PermissionRule)

    /**
     * Delete a specific permission rule from a project.
     * @param projectId The project ID
     * @param permission The permission type to match
     * @param pattern The pattern to match
     */
    suspend fun deleteRule(projectId: String, permission: String, pattern: String)
}
