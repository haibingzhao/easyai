package com.easy.easyai.core.permission

import java.nio.file.Path

/**
 * AI-powered risk check for shell commands that static rules could not
 * confidently classify (i.e. the rule evaluation fell through to ASK).
 *
 * The checker receives the command plus the project's effective read/write
 * permission rules and decides whether the command stays inside the allowed
 * scope. Implementations live outside easyai-core (they need a ChatModel),
 * core only defines the contract.
 */
interface ShellAiRiskChecker {

    /**
     * Evaluate whether executing [command] is risky given the effective
     * permission [rules] (including file.read/write scope config).
     *
     * @param command The shell command to evaluate
     * @param projectPath Project root path for path-scoped rules
     * @param userId Owner of the model config being used
     * @param modelConfigId Model config selected by the user for AI checking
     * @return [AiRiskResult.allowed]=true when the command is deemed safe
     */
    suspend fun checkRisk(
        command: String,
        projectPath: Path?,
        userId: String?,
        modelConfigId: String,
        rules: List<PermissionRule>
    ): AiRiskResult
}

/**
 * Result of an AI risk check.
 *
 * @property allowed True when the AI judge considers the command safe
 * @property reason Human-readable explanation (risk description when not allowed)
 */
data class AiRiskResult(
    val allowed: Boolean,
    val reason: String? = null
)
