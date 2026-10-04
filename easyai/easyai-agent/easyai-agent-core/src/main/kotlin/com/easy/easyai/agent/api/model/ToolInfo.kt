package com.easy.easyai.agent.api.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * DTO for available tool information returned by the API.
 *
 * The availability flags are pre-computed here rather than exposing raw capabilities, so clients
 * (notably the console) can filter without re-implementing the derivation rules.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolInfo(
    val name: String,
    val description: String,
    val permissionCategory: String = name,
    val uiRenderer: String = "generic",
    val isDefaultTool: Boolean = true,
    /**
     * Whether this tool is auto-injected by the runtime and bypasses agent-level
     * toolNames filtering (e.g. team coordination tools). Such tools should not be
     * offered for manual selection in configuration UIs: selecting them is redundant
     * when they apply, and meaningless when they don't.
     */
    val alwaysInclude: Boolean = false,
    /** Whether this tool cannot run for a SUBAGENT (recursion guard / main-agent-only). */
    val blockedForSubAgent: Boolean = false,
    /** Whether this tool can never function for a TEAM leader (no sub-agent whitelist). */
    val unusableForTeam: Boolean = false,
    /** Whether the swarm runtime does not support this tool for worker agents. */
    val unsupportedInSwarm: Boolean = false
)