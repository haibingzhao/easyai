package com.easy.easyai.core.agent

/**
 * Agent role type — controls visibility and invocation rules.
 *
 * - [PRIMARY]: Only usable as a main agent selected by the user.
 * - [SUBAGENT]: Only invocable via the subagent tool, not selectable by the user.
 * - [ALL]: Can be used as both primary agent and sub-agent.
 */
enum class AgentType {
    PRIMARY,
    SUBAGENT,
    ALL;

    companion object {
        fun fromString(value: String?): AgentType =
            when (value?.uppercase()) {
                "SUBAGENT" -> SUBAGENT
                "ALL" -> ALL
                else -> PRIMARY
            }
    }
}
