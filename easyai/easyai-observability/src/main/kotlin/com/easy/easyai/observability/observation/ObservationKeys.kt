package com.easy.easyai.observability.observation

/**
 * Utility object for generating observation keys.
 *
 * Keys are used to track active observations in maps, ensuring proper lifecycle management.
 */
object ObservationKeys {

    const val AGENT_PREFIX = "agent"
    const val ACTION_PREFIX = "action"
    const val TOOL_PREFIX = "tool"
    const val TOOL_LOOP_PREFIX = "tool-loop"
    const val LLM_PREFIX = "llm:"

    /**
     * Generates a key for agent observations.
     * Format: "agent:{runId}"
     */
    fun agentKey(runId: String): String = "$AGENT_PREFIX:$runId"

    /**
     * Generates a key for action observations.
     * Format: "action:{runId}:{actionName}"
     */
    fun actionKey(runId: String, actionName: String): String = "$ACTION_PREFIX:$runId:$actionName"

    /**
     * Generates a key for tool call observations.
     * Format: "tool:{runId}:{toolName}"
     */
    fun toolKey(runId: String, toolName: String): String = "$TOOL_PREFIX:$runId:$toolName"

    /**
     * Generates a key for tool loop observations.
     * Format: "tool-loop:{runId}:{interactionId}"
     */
    fun toolLoopKey(runId: String, interactionId: String): String = "$TOOL_LOOP_PREFIX:$runId:$interactionId"

    /**
     * Generates a key for LLM call observations.
     * Format: "llm:{runId}:{interactionId}"
     */
    fun llmKey(runId: String, interactionId: String): String = "$LLM_PREFIX$runId:$interactionId"

    /**
     * Generates span name for tool calls.
     * Format: "tool:{toolName}"
     */
    fun toolSpanName(toolName: String): String = "tool:$toolName"

    /**
     * Generates span name for tool loops.
     * Format: "tool-loop:{interactionId}"
     */
    fun toolLoopSpanName(interactionId: String): String = "tool-loop:$interactionId"
}
