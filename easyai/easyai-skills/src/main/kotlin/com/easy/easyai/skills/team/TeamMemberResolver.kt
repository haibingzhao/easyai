package com.easy.easyai.skills.team

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.skills.subagent.SubAgentTool

/**
 * Shared helpers for resolving a team member's [AgentDefinition] and effective [AgentContext].
 *
 * Extracted to eliminate duplication between [DelegateToMemberTool] and [ResumeMemberTool].
 */
internal object TeamMemberResolver {

    /** Result of resolving a team member's definition and effective context. */
    data class ResolvedMember(
        val definition: AgentDefinition,
        val effectiveContext: AgentContext,
    )

    /**
     * Look up a team member's [AgentDefinition] (DB with inline fallback) and
     * compute the effective [AgentContext] with inline MCP/skill bindings applied.
     *
     * @return the resolved pair, or `null` if the member is not found.
     */
    suspend fun resolve(
        memberId: String,
        agentContext: AgentContext,
        agentStore: AsyncAgentStore,
    ): ResolvedMember? {
        val userId = agentContext.userId ?: "system"
        val definition = agentStore.findById(memberId, userId)
            ?: resolveInlineMember(memberId, agentContext)
            ?: return null

        val effectiveContext = resolveEffectiveContext(definition, agentContext)
        return ResolvedMember(definition, effectiveContext)
    }

    /**
     * Resolve an inline custom member from [AgentContext.teamMembers].
     * Returns null if the memberId does not match any inline entry.
     */
    fun resolveInlineMember(memberId: String, agentContext: AgentContext): AgentDefinition? {
        val inlineEntry = agentContext.teamMembers.find {
            (it["inline"] == true) && (it["id"] == memberId || it["name"] == memberId)
        } ?: return null
        return SubAgentTool.synthesizeInlineDefinition(inlineEntry)
    }

    /**
     * For an inline member, build an [AgentContext] with explicit MCP configs and skill
     * whitelist injected (inline members have no agent_tool DB rows).
     * For DB-backed members, returns the original context unchanged.
     */
    fun resolveEffectiveContext(definition: AgentDefinition, agentContext: AgentContext): AgentContext {
        val inlineEntry = if (definition.id.startsWith("inline:")) {
            agentContext.teamMembers.find { it["id"] == definition.id }
        } else null
        return if (inlineEntry != null) {
            agentContext.copy(
                mcpConfigs = SubAgentTool.parseInlineMcpConfigs(inlineEntry),
                allowedSkillNames = SubAgentTool.parseInlineSkillNames(inlineEntry)
            )
        } else agentContext
    }
}
