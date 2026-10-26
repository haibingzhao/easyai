package com.easy.easyai.core.tool

/**
 * Semantic capabilities a tool provides, declared via [ToolMetadata.capabilities].
 *
 * Consumers (prompt building, steering hints, sub-agent/team tool filtering,
 * config validation) match on capabilities instead of hard-coding tool names.
 *
 * Declaration is a contract, not an option: [ToolMetadata.capabilities] defaults to empty and the
 * sub-agent/team-member filters are denylists built from it, so a tool that spawns sub-agents,
 * pauses the loop for user input, or coordinates team members but declares nothing is passed
 * straight through to sub-agents and team members with no warning. Any new tool with those
 * behaviours must declare the matching capability.
 */
enum class ToolCapability {
    /** Tool spawns sub-agents (e.g. `task`). Blocked for sub-agents and TEAM leaders. */
    SPAWNS_SUBAGENTS,

    /** Tool pauses the loop to interact with the user (e.g. `ask_question`). Blocked for sub-agents and team members. */
    USER_INTERACTIVE,

    /** Tool executes shell commands (e.g. `bash`). Enables shell-specific steering hints. */
    SHELL_EXECUTION,

    /** Tool coordinates team members (e.g. `delegate_to_member`). Blocked for team members to prevent nesting. */
    TEAM_COORDINATION,

    /** Tool loads skill content at runtime (e.g. `load_skill`). Unavailable in swarm worker context. */
    SKILL_LOADING,

    /**
     * Tool only reads (web fetch/search, memory search/read/list, file read/grep/glob/ls) and never
     * changes local or remote state. This is the allowlist for unattended watch runs: a tool without
     * it could write files, run commands or pause for input, none of which a background run may do.
     * MCP tools never declare it — a remote tool's side effects are not knowable here.
     */
    READ_ONLY
}
