package com.easy.easyai.core.agent

/**
 * Compute the log prefix for agent classes based on whether the agent
 * is a sub-agent. Returns "[Agent-SubAgent] " for sub-agents, "[Agent]" otherwise.
 */
internal fun agentLogPrefix(agentContext: AgentContext): String =
        if (agentContext.parentAgentId != null)
            "[${agentContext.agentId}-${agentContext.parentAgentId}]" else "[${agentContext.agentId}]"