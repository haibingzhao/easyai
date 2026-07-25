package com.easy.easyai.core.agent

/**
 * Agent role type — controls visibility and invocation rules.
 *
 * - [PRIMARY]: Only usable as a main agent selected by the user.
 * - [SUBAGENT]: Only invocable via the subagent tool, not selectable by the user.
 * - [TEAM]: Team leader that coordinates member agents via delegate/wait/resume tools.
 *   Selectable by the user in Chat (like PRIMARY), but not invocable as a sub-agent.
 * - [ALL]: Can be used as both primary agent and sub-agent.
 */
enum class AgentType {
    PRIMARY,
    SUBAGENT,
    TEAM,
    ALL;

    companion object {
        fun fromString(value: String?): AgentType =
            when (value?.uppercase()) {
                "SUBAGENT" -> SUBAGENT
                "TEAM" -> TEAM
                "ALL" -> ALL
                else -> PRIMARY
            }
    }
}
