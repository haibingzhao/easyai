package com.easy.easyai.core.tool

/**
 * Single source of truth for the "this tool cannot function in that runtime" rules, derived from
 * [ToolBuilder.capabilities] and [ToolBuilder.mainAgentOnly] rather than hard-coded tool names.
 *
 * Both the config validators (easyai-web) and the tool DTO served to the console read these
 * predicates, so each rule is written once instead of being mirrored per consumer or per language.
 */
object ToolAvailabilityRules {

    /**
     * A SUBAGENT always runs under a parent: spawning further sub-agents is recursion-guarded and
     * main-agent-only tools are not built for it.
     */
    @JvmStatic
    fun blockedForSubAgent(builder: ToolBuilder): Boolean =
        builder.mainAgentOnly || ToolCapability.SPAWNS_SUBAGENTS in builder.capabilities

    /** A TEAM leader coordinates members and has no sub-agent whitelist, so spawning can never work. */
    @JvmStatic
    fun unusableForTeam(builder: ToolBuilder): Boolean =
        ToolCapability.SPAWNS_SUBAGENTS in builder.capabilities

    /**
     * Swarm workers run without the main agent's privileges and without skills, so main-agent-only,
     * sub-agent-spawning and skill-loading tools are all unavailable.
     */
    @JvmStatic
    fun unsupportedInSwarm(builder: ToolBuilder): Boolean =
        builder.mainAgentOnly ||
            ToolCapability.SPAWNS_SUBAGENTS in builder.capabilities ||
            ToolCapability.SKILL_LOADING in builder.capabilities
}
