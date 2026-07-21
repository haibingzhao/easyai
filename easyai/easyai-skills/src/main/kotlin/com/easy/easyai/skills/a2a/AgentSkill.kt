package com.easy.easyai.skills.a2a

/**
 * A2A protocol skill metadata.
 * Used for AgentCard skill discovery between agents.
 */
data class AgentSkill(
    val id: String,
    val name: String,
    val description: String,
    val tags: List<String>,
    val examples: List<String>? = null,
    val inputModes: List<String>? = null,
    val outputModes: List<String>? = null,
)