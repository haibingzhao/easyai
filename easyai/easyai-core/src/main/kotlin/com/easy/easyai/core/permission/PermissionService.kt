package com.easy.easyai.core.permission

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
     * @return The permission check result
     */
    suspend fun evaluateWithContext(
        projectId: String,
        projectPath: Path?,
        toolName: String,
        arguments: Map<String, Any?>,
        userId: String? = null
    ): PermissionCheckResult {
        val builder = findBuilder(toolName)
        if (builder == null) {
            // MCP tools have no ToolBuilder; evaluate the mcp.use rule instead.
            if (isMcpTool(toolName)) {
                return evaluateMcpPermission(projectId, toolName, arguments)
            }
            logger.debug("No builder found for tool '{}', skipping permission check", toolName)
            return PermissionCheckResult(
                action = PermissionAction.ALLOW,
                permission = "tool.execute.$toolName",
                pattern = extractPattern(toolName, arguments)
            )
        }
        val evaluator = builder.permissionEvaluator
        val userRules = ruleStore.loadRules(projectId)
        val defaults = builder.defaultPermissionRules
        val rules = defaults + userRules

        val ctx = PermissionEvalContext(rules, projectPath, arguments, this, userId)
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
        userId: String? = null
    ): PermissionCheckResult {
        if (projectId == null) {
            logger.debug("No projectId in BeforeToolCallContext, allowing tool call {} ({})", toolName, toolCallId)
            return PermissionCheckResult(
                action = PermissionAction.ALLOW,
                permission = getRulePermissionType(toolName),
                pattern = extractPattern(toolName, arguments)
            )
        }
        return evaluateWithContext(projectId, projectPath, toolName, arguments, userId)
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
     * Adds or removes rules to match the desired setting value.
     */
    suspend fun updateSetting(projectId: String, key: String, value: Any?) {
        val currentSettings = getEffectiveSettings(projectId)
        val updatedSettings = applySettingUpdate(currentSettings, key, value)
        val newRules = DefaultPermissionSettings.toUserRules(updatedSettings)
        ruleStore.saveRules(projectId, newRules)
        logger.info("Updated setting {} for project {}", key, projectId)
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
        userId: String?
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
            val aiResult = checkAiRisk(command, projectPath, userId, aiModelConfigId, rules)
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
        rules: List<PermissionRule>
    ): AiRiskResult {
        val checker = aiRiskChecker ?: return AiRiskResult(allowed = false)
        val cacheKey = "$command|$modelConfigId|${rules.hashCode()}"
        synchronized(aiRiskCache) { aiRiskCache[cacheKey] }?.let { return it }

        val result = try {
            withTimeout(AI_CHECK_TIMEOUT_MS.milliseconds) {
                checker.checkRisk(command, projectPath, userId, modelConfigId, rules)
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

    /**
     * Apply a setting update to produce new settings.
     */
    private fun applySettingUpdate(current: DefaultPermissionSettings.Settings, key: String, value: Any?): DefaultPermissionSettings.Settings {
        return when (key) {
            "readFileProject" -> current.copy(readFileProject = value as Boolean)
            "readFileAll" -> current.copy(readFileAll = value as Boolean)
            "writeFileProject" -> current.copy(writeFileProject = value as Boolean)
            "writeFileAll" -> current.copy(writeFileAll = value as Boolean)
            "executeSafeCommands" -> current.copy(executeSafeCommands = value as Boolean)
            "executeAllCommands" -> current.copy(executeAllCommands = value as Boolean)
            "useBrowser" -> current.copy(useBrowser = value as Boolean)
            "useMcp" -> current.copy(useMcp = value as Boolean)
            "readOtherPaths" -> current.copy(readOtherPaths = @Suppress("UNCHECKED_CAST") (value as List<String>))
            "writeOtherPaths" -> current.copy(writeOtherPaths = @Suppress("UNCHECKED_CAST") (value as List<String>))
            "otherCommands" -> current.copy(otherCommands = @Suppress("UNCHECKED_CAST") (value as List<String>))
            "aiCheckModelId" -> current.copy(aiCheckModelId = value as? String)
            else -> current
        }
    }

    companion object {
        private val DEFAULT_PATTERN_KEYS = listOf("command", "cmd", "path", "file", "filepath", "file_path", "url")

        /** Timeout for a single AI risk check LLM call. */
        private const val AI_CHECK_TIMEOUT_MS = 15_000L

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
        projectId: String,
        toolName: String,
        arguments: Map<String, Any?>
    ): PermissionCheckResult {
        val userRules = ruleStore.loadRules(projectId)
        val result = PermissionEvaluator.evaluate("mcp.use", "*", userRules)
        val pattern = extractPattern(toolName, arguments)
        if (result.action == PermissionAction.DENY) {
            logger.debug("MCP permission DENY for tool '{}', project '{}'", toolName, projectId)
            return PermissionCheckResult(PermissionAction.DENY, "mcp.use", pattern)
        }
        logger.trace("MCP permission check: project={}, tool={}, action={}", projectId, toolName, result.action)
        return PermissionCheckResult(PermissionAction.ALLOW, "mcp.use", pattern)
    }
}
