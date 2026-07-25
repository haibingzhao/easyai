package com.easy.easyai.core.prompt

/**
 * Context for building system prompts at runtime.
 * Assembled from protocol (for provider prompt lookup) and Agent's customInstructions.
 * @param protocol the model provider protocol, e.g. "OPENAI", "ANTHROPIC"
 */
data class AgentPromptConfig(
    val protocol: String? = null,
    val customInstructions: String? = null,
    val environmentInfo: String? = null,
    val scriptLlmSegment: String? = null,
    val skillsList: String? = null,
    val subAgentsList: String? = null,
    val teamMembersList: String? = null,
    val toolsList: String? = null,
    val instructionsSegment: String? = null,
    val memorySegment: String? = null,
    val outputSchemaSegment: String? = null,
    val cwd: String? = null,
    val os: String? = null
)
