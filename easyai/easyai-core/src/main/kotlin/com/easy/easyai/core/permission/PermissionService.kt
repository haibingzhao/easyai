package com.easy.easyai.core.permission

import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.nio.file.Path
import kotlin.time.Duration.Companion.milliseconds

/**
 * Core permission service that evaluates tool call permissions and manages rules.
 *
 * Delegates permission evaluation to each ToolBuilder's [ToolBuilder.permissionEvaluator],
 * making the system open for extension but closed for modification.
 * When a new tool is added, only its ToolBuilder needs to declare the appropriate evaluator.
 *
 * @property ruleStore The storage backend for permission rules
 * @property toolFactory The tool factory for looking up tool metadata
 */
class PermissionService(
    private val ruleStore: PermissionRuleStore,
    private val toolFactory: ToolFactory? = null,
    private val aiRiskChecker: ShellAiRiskChecker? = null
) : SharedPermissionEvaluator {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Cached mapping from tool name to ToolBuilder.
     * Avoids re-scanning builders on every permission check.
     */
    @Volatile
    private var builderCache: Map<String, ToolBuilder>? = null

    /**
     * LRU cache of AI risk check results, keyed by command + rules digest.
     * Prevents repeated LLM calls for the same command against the same config.
     * Access is synchronized: cache hits are rare enough that contention is negligible.
     */
    private val aiRiskCache = object : LinkedHashMap<String, AiRiskResult>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AiRiskResult>): Boolean {
            return size > MAX_AI_RISK_CACHE_ENTRIES
        }
    }

    /**
     * Evaluate permission for a tool call with project path context.
     * Delegates to the ToolBuilder's permissionEvaluator.
     *
     * @param projectId The project ID for rule lookup
     * @param projectPath The project root path for project-scoped checks
     * @param toolName The name of the tool being called
     * @param arguments The tool call arguments
     * @param userId Session owner, used for user-scoped lookups (AI risk check model config)
     * @param projectKind Kind of the bound project; a temporary workspace reads the default bucket
     * @param owners Chat boundary's read-visibility set, used to find the group default bucket
     * @return The permission check result
     */
    suspend fun evaluateWithContext(
        projectId: String,
        projectPath: Path?,
        toolName: String,
        arguments: Map<String, Any?>,
        userId: String? = null,
        projectKind: ProjectKind = ProjectKind.USER,
        owners: Collection<String> = emptySet()
    ): PermissionCheckResult {
        val ruleScope = PermissionScope.forProject(projectId, projectKind, userId, owners)
        val builder = findBuilder(toolName)
        if (builder == null) {
            // MCP tools have no ToolBuilder; evaluate the mcp.use rule instead.
            if (isMcpTool(toolName)) {
                return evaluateMcpPermission(ruleScope, toolName, arguments)
            }
            logger.debug("No builder found for tool '{}', skipping permission check", toolName)
            return PermissionCheckResult(
                action = PermissionAction.ALLOW,
                permission = "tool.execute.$toolName",
                pattern = extractPattern(toolName, arguments)
            )
        }
        val evaluator = builder.permissionEvaluator
        val userRules = ruleStore.loadRules(ruleScope)
        val defaults = builder.defaultPermissionRules
        val rules = defaults + userRules

        val ctx = PermissionEvalContext(rules, projectPath, arguments, this, userId, owners)
        val result = evaluator.evaluate(ctx)
        if(result.action == PermissionAction.DENY) {
            logger.debug("Permission check DENY for tool '{}', project '{}', arguments: {}", toolName, projectId, arguments)
        } else {
            logger.trace("Permission check: project={}, tool={}, builder={}, action={}, permission={}, pattern={}",
                projectId, toolName, builder.name, result.action, result.permission, result.pattern)
        }
        return result
    }

    /**
     * Evaluate permission for a tool call using BeforeToolCallContext.
     * Convenience method for use in beforeToolCall callbacks.
     */
    suspend fun evaluateForToolCall(
        toolCallId: String,
        toolName: String,
        arguments: Map<String, Any?>,
        projectId: String?,
        projectPath: Path?,
        userId: String? = null,
        projectKind: ProjectKind = ProjectKind.USER,
        owners: Collection<String> = emptySet()
    ): PermissionCheckResult {
        if (projectId == null) {
            logger.debug("No projectId in BeforeToolCallContext, allowing tool call {} ({})", toolName, toolCallId)
            return PermissionCheckResult(
                action = PermissionAction.ALLOW,
                permission = getRulePermissionType(toolName),
                pattern = extractPattern(toolName, arguments)
            )
        }
        return evaluateWithContext(projectId, projectPath, toolName, arguments, userId, projectKind, owners)
    }

    /**
     * Add an "always allow" rule for a specific permission and pattern.
     */
    suspend fun addAllowRule(projectId: String, permission: String, pattern: String) {
        val rule = PermissionRule(
            permission = permission,
            pattern = pattern,
            action = PermissionAction.ALLOW
        )
        ruleStore.addRule(projectId, rule)
        logger.info("Added allow rule: {} {} for project {}", permission, pattern, projectId)
    }

    /**
     * Add a "deny" rule for a specific permission and pattern.
     */
    suspend fun addDenyRule(projectId: String, permission: String, pattern: String) {
        val rule = PermissionRule(
            permission = permission,
            pattern = pattern,
            action = PermissionAction.DENY
        )
        ruleStore.addRule(projectId, rule)
        logger.info("Added deny rule: {} {} for project {}", permission, pattern, projectId)
    }

    /**
     * Get all permission rules for a project.
     */
    suspend fun getRules(projectId: String): List<PermissionRule> {
        return ruleStore.loadRules(projectId)
    }

    /**
     * Get effective permission settings for a project (defaults + user rules).
     */
    suspend fun getEffectiveSettings(projectId: String): DefaultPermissionSettings.Settings {
        val rules = ruleStore.loadRules(projectId)
        return DefaultPermissionSettings.getEffectiveSettings(rules)
    }

    /**
     * Update a single permission setting for a project.
     *
     * Only the rules addressed by [key] are rewritten; every other rule in the bucket is preserved.
     * Rewriting the whole bucket through [DefaultPermissionSettings.toUserRules] would drop rules
     * the settings UI cannot express (e.g. `tool.execute.*` or pattern-specific DENY rules). That is
     * especially destructive for the shared default bucket, where project-less sessions also record
     * "always allow/deny" rules.
     */
    suspend fun updateSetting(projectId: String, key: String, value: Any?) {
        val current = ruleStore.loadRules(projectId)
        val merged = when (key) {
            "readFileProject" -> replaceToggle(current, "file.read.project", value as Boolean, PermissionAction.DENY)
            "readFileAll" -> replaceToggle(current, "file.read.all", value as Boolean, PermissionAction.ALLOW)
            "writeFileProject" -> replaceToggle(current, "file.write.project", value as Boolean, PermissionAction.DENY)
            "writeFileAll" -> replaceToggle(current, "file.write.all", value as Boolean, PermissionAction.ALLOW)
            "executeSafeCommands" -> replaceToggle(current, "shell.safe", value as Boolean, PermissionAction.DENY)
            "executeAllCommands" -> replaceToggle(current, "shell.all", value as Boolean, PermissionAction.ALLOW)
            "useBrowser" -> replaceToggle(current, "browser.use", value as Boolean, PermissionAction.DENY)
            "useMcp" -> replaceToggle(current, "mcp.use", value as Boolean, PermissionAction.DENY)
            "readOtherPaths" -> replaceAllowRules(
                current, "file.read.other", @Suppress("UNCHECKED_CAST") (value as List<String>)
            )
            "writeOtherPaths" -> replaceAllowRules(
                current, "file.write.other", @Suppress("UNCHECKED_CAST") (value as List<String>)
            )
            "otherCommands" -> replaceAllowRules(
                current,
                "shell.other",
                @Suppress("UNCHECKED_CAST") (value as List<String>)
                    .map { if (it.endsWith("*")) it else "$it*" }
            )
            "aiCheckModelId" -> replaceAiCheckModel(current, value as? String)
            // Unknown keys must not rewrite the bucket; a no-op is safer than dropping rules.
            else -> return
        }
        ruleStore.saveRules(projectId, merged)
        logger.info("Updated setting {} for project {}", key, projectId)
    }

    /**
     * Replace the whole-permission rule a boolean setting owns, leaving other permissions untouched.
     * [nonDefaultAction] is the action the setting writes when it differs from its default:
     * DENY for toggles that default to enabled, ALLOW for toggles that default to disabled.
     */
    private fun replaceToggle(
        rules: List<PermissionRule>,
        permission: String,
        enabled: Boolean,
        nonDefaultAction: PermissionAction
    ): List<PermissionRule> {
        val remaining = rules.filterNot { it.permission == permission }
        val needsRule = if (nonDefaultAction == PermissionAction.ALLOW) enabled else !enabled
        return if (needsRule) {
            remaining + PermissionRule(permission, "*", nonDefaultAction)
        } else {
            remaining
        }
    }

    /** Replace the ALLOW rules that represent a list setting, preserving DENY rules for the same permission. */
    private fun replaceAllowRules(
        rules: List<PermissionRule>,
        permission: String,
        patterns: List<String>
    ): List<PermissionRule> {
        val remaining = rules.filterNot { it.permission == permission && it.action == PermissionAction.ALLOW }
        return remaining + patterns.distinct().map { PermissionRule(permission, it, PermissionAction.ALLOW) }
    }

    private fun replaceAiCheckModel(rules: List<PermissionRule>, modelId: String?): List<PermissionRule> {
        val remaining = rules.filterNot {
            it.permission == "shell.ai" && it.action == PermissionAction.ALLOW
        }
        return if (modelId.isNullOrBlank()) {
            remaining
        } else {
            remaining + PermissionRule("shell.ai", modelId, PermissionAction.ALLOW)
        }
    }

    // --- SharedPermissionEvaluator implementation ---

    /**
     * Evaluate file read/write permission.
     * Priority: all > project > other > ASK
     */
    override fun evaluateFilePermission(
        rules: List<PermissionRule>,
        projectPath: Path?,
        arguments: Map<String, Any?>,
        read: Boolean
    ): PermissionCheckResult {
        val prefix = if (read) "file.read" else "file.write"
        val filePath = extractFilePath(arguments)
        val pattern = filePath ?: "*"

        // 1. Check {prefix}.all
        val allResult = PermissionEvaluator.evaluate("$prefix.all", "*", rules)
        if (allResult.action != PermissionAction.ASK) {
            return PermissionCheckResult(allResult.action, "$prefix.all", pattern)
        }

        // 2. Check {prefix}.project (only if path is under projectPath)
        if (projectPath != null && filePath != null) {
            val normalizedPath = SafeCommandDetector.normalizePath(filePath, projectPath)
            if (normalizedPath.startsWith(projectPath)) {
                val projectResult = PermissionEvaluator.evaluate("$prefix.project", "*", rules)
                if (projectResult.action != PermissionAction.ASK) {
                    return PermissionCheckResult(projectResult.action, "$prefix.project", pattern)
                }
            }
        } else if (projectPath != null) {
            // No specific file path, treat as project-scoped
            val projectResult = PermissionEvaluator.evaluate("$prefix.project", "*", rules)
            if (projectResult.action != PermissionAction.ASK) {
                return PermissionCheckResult(projectResult.action, "$prefix.project", pattern)
            }
        }

        // 3. Check {prefix}.other (wildcard + directory prefix match)
        if (filePath != null) {
            val otherResult = PermissionEvaluator.evaluateFilePath("$prefix.other", filePath, rules)
            if (otherResult.action != PermissionAction.ASK) {
                return PermissionCheckResult(otherResult.action, "$prefix.other", filePath)
            }
            // 3.1 Fallback: normalized path under the system temp dir → ALLOW.
            // Covers Windows backslash / case / separator mismatches that string-prefix matching
            // cannot handle (ToolResultGuard spills oversized results under java.io.tmpdir).
            // Explicit user rules were evaluated above and stay honored.
            if (isUnderSystemTmpDir(filePath)) {
                return PermissionCheckResult(PermissionAction.ALLOW, "$prefix.other", filePath)
            }
        }

        // 4. Default: ASK — report correct permission type based on path location
        val isUnderProject = projectPath != null && filePath != null &&
            SafeCommandDetector.normalizePath(filePath, projectPath).startsWith(projectPath)
        val defaultPermission = if (isUnderProject) "$prefix.project" else "$prefix.other"
        return PermissionCheckResult(PermissionAction.ASK, defaultPermission, pattern)
    }

    /** True when [filePath] resolves (after normalization) to a location under the system temp dir. */
    private fun isUnderSystemTmpDir(filePath: String): Boolean {
        val tmpDir = System.getProperty("java.io.tmpdir") ?: return false
        return try {
            val normalizedPath = Path.of(filePath).toAbsolutePath().normalize()
            val normalizedTmp = Path.of(tmpDir).toAbsolutePath().normalize()
            normalizedPath.startsWith(normalizedTmp)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Evaluate shell command permission.
     * Uses SafeCommandDetector for classification, then checks rules.
     * When static rules fall through to ASK and the project has opted in via a
     * "shell.ai" ALLOW rule (pattern = model config id), an AI risk check runs
     * first: safe commands are allowed, risky ones keep the ASK with the AI's reason.
     */
    override suspend fun evaluateShellPermission(
        rules: List<PermissionRule>,
        projectPath: Path?,
        arguments: Map<String, Any?>,
        userId: String?,
        owners: Collection<String>
    ): PermissionCheckResult {
        val command = extractCommand(arguments)
        val pattern = command ?: "*"

        // 1. Check shell.all
        val allResult = PermissionEvaluator.evaluate("shell.all", "*", rules)
        if (allResult.action != PermissionAction.ASK) {
            return PermissionCheckResult(allResult.action, "shell.all", pattern)
        }

        // 2. Classify the command
        var isUnsafeCommand = false
        if (command != null) {
            when (val safety = SafeCommandDetector.classify(command)) {
                SafeCommandDetector.CommandSafety.SAFE_READ, SafeCommandDetector.CommandSafety.SAFE_WRITE -> {
                    // Check shell.safe rule
                    val safeResult = PermissionEvaluator.evaluate("shell.safe", "*", rules)
                    if (safeResult.action == PermissionAction.DENY) {
                        return PermissionCheckResult(PermissionAction.DENY, "shell.safe", pattern)
                    }
                    if (safeResult.action == PermissionAction.ALLOW) {
                        // Extra check: verify paths in the command have appropriate permissions
                        if (projectPath != null) {
                            val paths = SafeCommandDetector.extractPaths(command, projectPath)
                            for (path in paths) {
                                val isRead = safety == SafeCommandDetector.CommandSafety.SAFE_READ
                                val pathResult = evaluateFilePathPermission(rules, projectPath, path, isRead)
                                if (pathResult.action != PermissionAction.ALLOW) {
                                    return PermissionCheckResult(
                                        pathResult.action,
                                        pathResult.permission,
                                        path.toString()
                                    )
                                }
                            }
                        }
                        return PermissionCheckResult(PermissionAction.ALLOW, "shell.safe", pattern)
                    }
                    // shell.safe is ASK -> fall through
                }
                SafeCommandDetector.CommandSafety.UNSAFE -> {
                    isUnsafeCommand = true
                    // Check shell.other (prefix match with wildcard)
                    val otherResult = PermissionEvaluator.evaluate("shell.other", command, rules)
                    if (otherResult.action != PermissionAction.ASK) {
                        return PermissionCheckResult(otherResult.action, "shell.other", pattern)
                    }
                }
            }
        }

        // 3. Default: ASK — but run an AI risk check first when opted in via "shell.ai" rule.
        val defaultPermission = if (isUnsafeCommand) "shell.other" else "shell.safe"
        val aiModelConfigId = rules
            .lastOrNull { it.permission == "shell.ai" && it.action == PermissionAction.ALLOW }
            ?.pattern
        if (aiRiskChecker != null && !aiModelConfigId.isNullOrBlank() && command != null) {
            // Code that never appears in the command text can only yield an "unknown"
            // verdict, so ask a human without paying for a round trip.
            if (InterpreterScriptDetector.loadsInvisibleScript(command)) {
                logger.debug("AI risk check skipped for '{}': executed code is not visible", command)
                return PermissionCheckResult(
                    PermissionAction.ASK,
                    defaultPermission,
                    pattern,
                    INVISIBLE_SCRIPT_REASON
                )
            }
            val aiResult = checkAiRisk(command, projectPath, userId, aiModelConfigId, rules, owners)
            if (aiResult.allowed) {
                return PermissionCheckResult(PermissionAction.ALLOW, "shell.ai", pattern, aiResult.reason)
            }
            return PermissionCheckResult(PermissionAction.ASK, defaultPermission, pattern, aiResult.reason)
        }
        return PermissionCheckResult(PermissionAction.ASK, defaultPermission, pattern)
    }

    /**
     * Run the AI risk check with caching and safe fallback.
     * Any failure (timeout, LLM error, unavailable checker) yields a
     * non-allowed result so the caller falls back to the regular ASK flow.
     */
    private suspend fun checkAiRisk(
        command: String,
        projectPath: Path?,
        userId: String?,
        modelConfigId: String,
        rules: List<PermissionRule>,
        owners: Collection<String>
    ): AiRiskResult {
        val checker = aiRiskChecker ?: return AiRiskResult(allowed = false)
        val cacheKey = "$command|$modelConfigId|${rules.hashCode()}"
        synchronized(aiRiskCache) { aiRiskCache[cacheKey] }?.let { return it }

        val result = try {
            withTimeout(AI_CHECK_TIMEOUT_MS.milliseconds) {
                checker.checkRisk(command, projectPath, userId, modelConfigId, rules, owners)
            }
        } catch (e: TimeoutCancellationException) {
            logger.warn("AI risk check timed out for command '{}'", command)
            AiRiskResult(allowed = false, reason = "AI 检查超时")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("AI risk check failed for command '{}': {}", command, e.message)
            AiRiskResult(allowed = false, reason = "AI 检查不可用")
        }
        synchronized(aiRiskCache) { aiRiskCache[cacheKey] = result }
        return result
    }

    /**
     * Evaluate a simple permission type (browser.use, mcp.use, tool.execute.*, etc.)
     */
    override fun evaluateSimplePermission(permission: String, rules: List<PermissionRule>): PermissionCheckResult {
        val result = PermissionEvaluator.evaluate(permission, "*", rules)
        return PermissionCheckResult(result.action, permission, "*")
    }

    // --- Helper methods ---

    /**
     * Evaluate file path permission for shell command path checking.
     */
    private fun evaluateFilePathPermission(
        rules: List<PermissionRule>,
        projectPath: Path?,
        filePath: Path,
        read: Boolean
    ): PermissionCheckResult {
        val prefix = if (read) "file.read" else "file.write"

        // Check file.{read/write}.all
        val allResult = PermissionEvaluator.evaluate("$prefix.all", "*", rules)
        if (allResult.action != PermissionAction.ASK) return PermissionCheckResult(allResult.action, "$prefix.all", filePath.toString())

        // Check if under projectPath -> file.{read/write}.project
        val isUnderProject = projectPath != null && filePath.startsWith(projectPath)
        if (isUnderProject) {
            val projectResult = PermissionEvaluator.evaluate("$prefix.project", "*", rules)
            if (projectResult.action != PermissionAction.ASK) return PermissionCheckResult(projectResult.action, "$prefix.project", filePath.toString())
        }

        // Check file.{read/write}.other (wildcard + directory prefix match)
        val otherResult = PermissionEvaluator.evaluateFilePath("$prefix.other", filePath.toString(), rules)
        if (otherResult.action != PermissionAction.ASK) return PermissionCheckResult(otherResult.action, "$prefix.other", filePath.toString())

        // Default: report the most specific permission type
        val defaultPermission = if (isUnderProject) "$prefix.project" else "$prefix.other"
        return PermissionCheckResult(PermissionAction.ASK, defaultPermission, filePath.toString())
    }

    /**
     * Find ToolBuilder by name using cache.
     */
    private fun findBuilder(toolName: String): ToolBuilder? {
        val cache = builderCache
        if (cache != null) return cache[toolName]
        // Build cache on first access
        val factory = toolFactory ?: return null
        val built = try {
            factory.getBuilders().associateBy { it.name }
        } catch (e: Exception) {
            logger.warn("Failed to build tool builder cache: {}", e.message)
            emptyMap()
        }
        builderCache = built
        return built[toolName]
    }

    /**
     * Get the rulePermissionType for a tool (used for saving permission rules).
     */
    fun getRulePermissionType(toolName: String): String {
        return findBuilder(toolName)?.rulePermissionType ?: "tool.execute.$toolName"
    }

    /**
     * Extract the pattern to match from tool arguments.
     * Delegates to the ToolBuilder's patternKeys.
     */
    fun extractPattern(toolName: String, arguments: Map<String, Any?>): String {
        val builder = findBuilder(toolName)
        val keys = builder?.patternKeys ?: DEFAULT_PATTERN_KEYS
        for (key in keys) {
            val value = arguments[key]
            if (value is String && value.isNotBlank()) return value
        }
        return if (builder?.defaultPatternWildcard != false) "*" else ""
    }

    /**
     * Extract file path from tool arguments.
     */
    private fun extractFilePath(arguments: Map<String, Any?>): String? {
        val keys = listOf("path", "file", "filepath", "file_path")
        for (key in keys) {
            val value = arguments[key]
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }

    /**
     * Extract command from tool arguments.
     */
    private fun extractCommand(arguments: Map<String, Any?>): String? {
        val keys = listOf("command", "cmd")
        for (key in keys) {
            val value = arguments[key]
            if (value is String && value.isNotBlank()) return value
        }
        return null
    }

    companion object {
        private val DEFAULT_PATTERN_KEYS = listOf("command", "cmd", "path", "file", "filepath", "file_path", "url")

        /**
         * Ceiling for one AI risk check. A checker budgets its own rounds (an unparsable verdict is
         * re-asked once, and each round bounds itself), so this is not the per-round timeout — it is
         * the guard against an implementation that never returns, and has to leave room for both of
         * the default checker's rounds.
         */
        private const val AI_CHECK_TIMEOUT_MS = 35_000L

        /** Upper bound of cached AI risk check results. */
        private const val MAX_AI_RISK_CACHE_ENTRIES = 200

        /** Verdict reason used when the executed code cannot be shown to the model. */
        private const val INVISIBLE_SCRIPT_REASON =
            "命令通过解释器执行脚本或模块，其内容未参与 AI 判定，需人工确认"

        /** MCP tool names follow the pattern "serverName__toolName" (double underscore separator). */
        internal fun isMcpTool(toolName: String): Boolean = toolName.contains("__")
    }

    /**
     * Evaluate permission for MCP tools by checking the "mcp.use" rule.
     * MCP tools have no ToolBuilder, so this method handles the permission
     * evaluation directly against stored rules.
     */
    private suspend fun evaluateMcpPermission(
        ruleScope: String,
        toolName: String,
        arguments: Map<String, Any?>
    ): PermissionCheckResult {
        val userRules = ruleStore.loadRules(ruleScope)
        val result = PermissionEvaluator.evaluate("mcp.use", "*", userRules)
        val pattern = extractPattern(toolName, arguments)
        if (result.action == PermissionAction.DENY) {
            logger.debug("MCP permission DENY for tool '{}', scope '{}'", toolName, ruleScope)
            return PermissionCheckResult(PermissionAction.DENY, "mcp.use", pattern)
        }
        logger.trace("MCP permission check: scope={}, tool={}, action={}", ruleScope, toolName, result.action)
        return PermissionCheckResult(PermissionAction.ALLOW, "mcp.use", pattern)
    }
}
