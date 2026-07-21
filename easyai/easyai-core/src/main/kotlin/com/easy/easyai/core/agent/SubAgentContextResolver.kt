package com.easy.easyai.core.agent

import com.easy.easyai.core.tool.ToolDefinition

/**
 * Resolves full AgentContext for a sub-agent, independently loading tools,
 * skills, and MCP configs from the agent_tool table — same as for a primary agent.
 *
 * Implementations are typically wired in the autoconfigure layer, bridging
 * easyai-core (where this interface lives) and easyai-repository (where
 * [SessionToolResolver] and skill resolution logic reside).
 */
interface SubAgentContextResolver {
    /**
     * Resolve tools, skills, allowedSkillNames, and instructions for the given sub-agent definition.
     *
     * The returned [AgentContext] must have:
     * - [AgentContext.agentId] set to [agentDef]'s ID (so MCP filtering uses the sub-agent's own MCP configs).
     * - [AgentContext.parentAgentId] set to the calling agent's ID, so that [com.easy.easyai.core.tool.ToolBuilder]
     *   implementations (e.g. SubAgentToolBuilder) can detect this is a sub-agent context and apply recursion guards.
     * - [AgentContext.promptTemplate] set to [agentDef]'s promptTemplate (not inherited from parent).
     * - [AgentContext.customInstructions] set to [agentDef]'s customInstructions (the caller may override it later).
     * - [AgentContext.subAgents] set to an empty list or the sub-agent's own SUBAGENT whitelist resolution.
     * - [AgentContext.skills] and [AgentContext.allowedSkillNames] resolved from the sub-agent's SKILL whitelist.
     * - [AgentContext.instructions] resolved according to [AgentDefinition.instructionsEnabled].
     *
     * Identity fields inherited from [parentContext] (sessionId, projectId, projectPath, userId, etc.)
     * are copied into the returned context; the caller in SubAgentTool overrides the remaining
     * runtime fields (modelConfig, agentRunId, etc.).
     *
     * @param agentDef The sub-agent's definition loaded from the agent store.
     * @param parentContext The parent agent's context (used to inherit sessionId, projectId, projectPath, userId).
     * @return Pair of (enriched AgentContext with skills/instructions, resolved tool list).
     */
    suspend fun resolve(
        agentDef: AgentDefinition,
        parentContext: AgentContext
    ): Pair<AgentContext, List<ToolDefinition>>
}
