package com.easy.easyai.core.agent

/**
 * Compute the log prefix for agent classes based on whether the agent
 * is a sub-agent. Returns "[SubAgent] " for sub-agents, "" otherwise.
 */
internal fun agentLogPrefix(parentAgentId: String?): String =
    if (parentAgentId != null) "[SubAgent] " else ""
