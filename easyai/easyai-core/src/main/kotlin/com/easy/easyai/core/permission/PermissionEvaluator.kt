package com.easy.easyai.core.permission

/**
 * Evaluates permission rules against a permission type and pattern.
 * Uses findLast semantics — later rules override earlier ones.
 */
object PermissionEvaluator {

    /**
     * Evaluate the matching rule for a given [permission] and [pattern].
     * Returns the last matching rule, or a default ASK rule if no match is found.
     *
     * @param permission The permission type (e.g., "tool.execute.shell")
     * @param pattern The pattern to match (e.g., "rm -rf *")
     * @param rules The list of rules to evaluate
     * @return The matching rule, or a default ASK rule
     */
    fun evaluate(permission: String, pattern: String, rules: List<PermissionRule>): PermissionRule {
        val match = rules.findLast { rule ->
            WildcardMatcher.matches(rule.permission, permission) &&
            WildcardMatcher.matches(rule.pattern, pattern)
        }
        return match ?: PermissionRule(permission, "*", PermissionAction.ASK)
    }

    /**
     * Evaluate rules for a file path with directory prefix semantics.
     * A rule pattern like "/tmp" matches "/tmp" itself and any path under "/tmp/".
     * Uses findLast semantics — later rules override earlier ones.
     *
     * @param permission The permission type (e.g., "file.write.other")
     * @param filePath The absolute file path being accessed
     * @param rules The list of rules to evaluate
     * @return The matching rule, or a default ASK rule
     */
    fun evaluateFilePath(permission: String, filePath: String, rules: List<PermissionRule>): PermissionRule {
        val match = rules.findLast { rule ->
            WildcardMatcher.matches(rule.permission, permission) &&
            WildcardMatcher.matchesFilePath(rule.pattern, filePath)
        }
        return match ?: PermissionRule(permission, "*", PermissionAction.ASK)
    }
}
