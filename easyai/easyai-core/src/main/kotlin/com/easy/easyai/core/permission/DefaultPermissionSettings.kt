package com.easy.easyai.core.permission

/**
 * Default permission settings and conversion between Settings and PermissionRule lists.
 *
 * Design: Only user-modified rules are stored in DB.
 * `getEffectiveSettings()` = DEFAULT + user rules overlay.
 */
object DefaultPermissionSettings {

    /**
     * Permission settings for a project.
     * Default values represent the state when no user rules exist.
     */
    data class Settings(
        val readFileProject: Boolean = true,
        val readFileAll: Boolean = false,
        val writeFileProject: Boolean = true,
        val writeFileAll: Boolean = false,
        val executeSafeCommands: Boolean = true,
        val executeAllCommands: Boolean = false,
        val useBrowser: Boolean = true,
        val useMcp: Boolean = true,
        val readOtherPaths: List<String> = emptyList(),
        val writeOtherPaths: List<String> = emptyList(),
        val otherCommands: List<String> = emptyList()
    )

    /** Default settings (no user rules). */
    val DEFAULT = Settings()

    /**
     * Convert non-default settings to PermissionRule list.
     * Only stores rules that differ from defaults.
     */
    fun toUserRules(settings: Settings): List<PermissionRule> {
        val rules = mutableListOf<PermissionRule>()

        // Boolean settings: only store if different from default
        if (!settings.readFileProject) {
            rules.add(PermissionRule("file.read.project", "*", PermissionAction.DENY))
        }
        if (settings.readFileAll) {
            rules.add(PermissionRule("file.read.all", "*", PermissionAction.ALLOW))
        }
        if (!settings.writeFileProject) {
            rules.add(PermissionRule("file.write.project", "*", PermissionAction.DENY))
        }
        if (settings.writeFileAll) {
            rules.add(PermissionRule("file.write.all", "*", PermissionAction.ALLOW))
        }
        if (!settings.executeSafeCommands) {
            rules.add(PermissionRule("shell.safe", "*", PermissionAction.DENY))
        }
        if (settings.executeAllCommands) {
            rules.add(PermissionRule("shell.all", "*", PermissionAction.ALLOW))
        }
        if (!settings.useBrowser) {
            rules.add(PermissionRule("browser.use", "*", PermissionAction.DENY))
        }
        if (!settings.useMcp) {
            rules.add(PermissionRule("mcp.use", "*", PermissionAction.DENY))
        }

        // List settings: each item becomes a rule
        for (path in settings.readOtherPaths) {
            rules.add(PermissionRule("file.read.other", path, PermissionAction.ALLOW))
        }
        for (path in settings.writeOtherPaths) {
            rules.add(PermissionRule("file.write.other", path, PermissionAction.ALLOW))
        }
        for (cmd in settings.otherCommands) {
            // Auto-append * for prefix matching
            val pattern = if (cmd.endsWith("*")) cmd else "$cmd*"
            rules.add(PermissionRule("shell.other", pattern, PermissionAction.ALLOW))
        }

        return rules
    }

    /**
     * Merge DB rules with defaults to produce effective settings.
     * Only user-modified rules should be in the DB; defaults fill in the rest.
     */
    fun getEffectiveSettings(rules: List<PermissionRule>): Settings {
        val readFileProject = !hasDenyRule(rules, "file.read.project")
        val readFileAll = hasAllowRule(rules, "file.read.all")
        val writeFileProject = !hasDenyRule(rules, "file.write.project")
        val writeFileAll = hasAllowRule(rules, "file.write.all")
        val executeSafeCommands = !hasDenyRule(rules, "shell.safe")
        val executeAllCommands = hasAllowRule(rules, "shell.all")
        val useBrowser = !hasDenyRule(rules, "browser.use")
        val useMcp = !hasDenyRule(rules, "mcp.use")

        val readOtherPaths = rules
            .filter { it.permission == "file.read.other" && it.action == PermissionAction.ALLOW }
            .map { it.pattern }
        val writeOtherPaths = rules
            .filter { it.permission == "file.write.other" && it.action == PermissionAction.ALLOW }
            .map { it.pattern }
        val otherCommands = rules
            .filter { it.permission == "shell.other" && it.action == PermissionAction.ALLOW }
            .map { it.pattern.removeSuffix("*") }

        return Settings(
            readFileProject = readFileProject,
            readFileAll = readFileAll,
            writeFileProject = writeFileProject,
            writeFileAll = writeFileAll,
            executeSafeCommands = executeSafeCommands,
            executeAllCommands = executeAllCommands,
            useBrowser = useBrowser,
            useMcp = useMcp,
            readOtherPaths = readOtherPaths,
            writeOtherPaths = writeOtherPaths,
            otherCommands = otherCommands
        )
    }

    private fun hasAllowRule(rules: List<PermissionRule>, permission: String): Boolean {
        return rules.any { it.permission == permission && it.action == PermissionAction.ALLOW }
    }

    private fun hasDenyRule(rules: List<PermissionRule>, permission: String): Boolean {
        return rules.any { it.permission == permission && it.action == PermissionAction.DENY }
    }
}
