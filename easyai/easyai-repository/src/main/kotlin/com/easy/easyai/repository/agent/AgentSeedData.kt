package com.easy.easyai.repository.agent

import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentType

/**
 * Seed data for default sub-agents.
 * These are inserted idempotently at startup by AgentSeedInitializer.
 */
object AgentSeedData {

    /**
     * Default sub-agent definitions to seed into the agent table.
     */
    val agents: List<AgentDefinition> = listOf(
        AgentDefinition.create(
            id = "subagent-explore",
            name = "explore",
            agentType = AgentType.SUBAGENT,
            description = "Fast codebase explorer (read-only). Use for finding files, understanding code structure, and searching patterns.",
            maxIterations = 20
        ),
        AgentDefinition.create(
            id = "subagent-research",
            name = "research",
            agentType = AgentType.SUBAGENT,
            description = "External research agent. Use for searching the web, reading documentation, and gathering external information.",
            maxIterations = 25
        )
    )

    /**
     * Tool whitelist entries for sub-agents.
     * Key: agentId, Value: list of tool names.
     * subagent-general has no entries (empty = inherit all tools).
     */
    val toolConfigs: Map<String, List<String>> = mapOf(
        "subagent-explore" to listOf("read", "grep", "glob", "ls"),
        "subagent-research" to listOf("bash", "grep", "read")
    )
}
