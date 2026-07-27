package com.easy.easyai.core.prompt

/**
 * Context data available for Jinja2 prompt template rendering.
 * This is the model map passed to the template engine.
 */
data class PromptContext(
    val agent: Map<String, Any?> = emptyMap(),
    val customInstructions: String? = null,
    val protocol: String? = null,
    val modelId: String? = null,
    val subAgents: List<Map<String, Any?>> = emptyList(),
    /** Team member data for TEAM agents (list of {id, name, description} maps). */
    val teamMembers: List<Map<String, Any?>> = emptyList(),
    /** Recovered team execution status (when restoring a TEAM session after restart). */
    val teamStatusSummary: String? = null,
    val skills: List<Map<String, Any?>> = emptyList(),
    val instructions: List<InstructionInfo> = emptyList(),
    val project: Map<String, Any?>? = null,
    val os: String = System.getProperty("os.name") ?: "unknown",
    val cwd: String? = null,
    val memory: String? = null,
    /** Tools available to the agent. Each map contains "name" and "description". */
    val tools: List<Map<String, Any?>> = emptyList(),
    /** JSON Schema for structured output enforcement. Non-null triggers output format instructions. */
    val outputSchema: String? = null,
    /** Structured input variables for template rendering. */
    val inputVariables: Map<String, Any?> = emptyMap(),
    /** Whether script LLM access is available (env vars injected into bash processes). */
    val scriptLlmAvailable: Boolean = false,
    /** Session-scoped variables that persist across compaction. Appended unconditionally to system prompt. */
    val sessionVariables: Map<String, String> = emptyMap(),
    /** Current date and time for injection into prompts. */
    val currentDateTime: String = java.time.ZonedDateTime.now()
        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z"))
) {
    /**
     * Convert to a flat map suitable for Jinja2 template rendering.
     * Null values are converted to empty strings for template compatibility.
     */
    fun toModel(): Map<String, Any> = mapOf(
        "agent" to agent,
        "custom_instructions" to (customInstructions ?: ""),
        "protocol" to (protocol ?: ""),
        "model_id" to (modelId ?: ""),
        "sub_agents" to subAgents,
        "team_members" to teamMembers,
        "team_status_summary" to (teamStatusSummary ?: ""),
        "skills" to skills,
        "instructions" to instructions,
        "project" to (project ?: emptyMap<String, Any>()),
        "os" to os,
        "cwd" to (cwd ?: ""),
        "memory" to (memory ?: ""),
        "tools" to tools,
        "input" to inputVariables,
        "current_date_time" to currentDateTime
    )
}
