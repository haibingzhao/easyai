package com.easy.easyai.web.service.validation

import com.easy.easyai.core.tool.ToolAvailabilityRules
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolFactory

/**
 * Derives tool-name sets for config validation from [ToolBuilder] metadata, replacing hard-coded
 * tool-name lists. The availability predicates live in [ToolAvailabilityRules] so that validation
 * and the console see the same answer.
 */
internal object ToolAvailability {
    /** Tools blocked at runtime for SUBAGENT agents (recursion guard / mainAgentOnly). */
    fun subAgentBlocked(toolFactory: ToolFactory): Set<String> =
        namesMatching(toolFactory, ToolAvailabilityRules::blockedForSubAgent)

    /** Tools that can never function for a TEAM leader (no sub-agent whitelist). */
    fun teamUnusable(toolFactory: ToolFactory): Set<String> =
        namesMatching(toolFactory, ToolAvailabilityRules::unusableForTeam)

    /** Tools the swarm runtime does not support for worker agents. */
    fun swarmUnsupported(toolFactory: ToolFactory): Set<String> =
        namesMatching(toolFactory, ToolAvailabilityRules::unsupportedInSwarm)

    /** Names of tools that load skill content at runtime (e.g. "load_skill"). */
    fun skillLoaders(toolFactory: ToolFactory): Set<String> = namesMatching(toolFactory) { builder ->
        ToolCapability.SKILL_LOADING in builder.capabilities
    }

    private fun namesMatching(toolFactory: ToolFactory, predicate: (ToolBuilder) -> Boolean): Set<String> =
        toolFactory.getBuilders().filter(predicate).map { it.name }.toSet()
}
