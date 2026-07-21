package com.easy.easyai.observability.observation

import io.micrometer.observation.Observation

/**
 * Custom observation context for EasyAI agent events.
 *
 * Extends Spring Observation's [Observation.Context] to integrate with the
 * Micrometer tracing ecosystem, enabling custom [io.micrometer.tracing.handler.TracingObservationHandler]
 * implementations that manage span hierarchies for agent execution.
 *
 * @since 2026.0.1
 */
class EasyAiObservationContext(
    val runId: String,
    val eventType: EventType,
    name: String,
    val parentRunId: String? = null
) : Observation.Context() {

    init {
        this.contextualName = name
    }

    /**
     * Returns the parent observation for explicit hierarchy management.
     * Useful for cross-thread scenarios where tracer.currentSpan() may be incorrect.
     */
    var parentObservation: Observation? = null

    /**
     * Event types that can be observed in the EasyAI agent lifecycle.
     */
    enum class EventType {
        AGENT_PROCESS,
        ACTION,
        TOOL_CALL,
        TOOL_LOOP,
        LLM_CALL,
        PLANNING,
        STATE_TRANSITION,
        LIFECYCLE,
        GOAL,
        RAG,
        RANKING,
        DYNAMIC_AGENT_CREATION,
        CUSTOM
    }

    companion object {
        /**
         * Creates a root agent observation context.
         */
        @JvmStatic
        fun rootAgent(runId: String, agentName: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.AGENT_PROCESS, agentName)

        /**
         * Creates a sub-agent observation context with a parent.
         */
        @JvmStatic
        fun subAgent(runId: String, agentName: String, parentRunId: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.AGENT_PROCESS, agentName, parentRunId)

        /**
         * Creates an action execution observation context.
         */
        @JvmStatic
        fun action(runId: String, actionName: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.ACTION, actionName)

        /**
         * Creates a tool call observation context.
         */
        @JvmStatic
        fun toolCall(runId: String, toolName: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.TOOL_CALL, "tool:$toolName")

        /**
         * Creates a tool loop observation context.
         */
        @JvmStatic
        fun toolLoop(runId: String, interactionId: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.TOOL_LOOP, "toolLoop:$interactionId")

        /**
         * Creates an LLM call observation context.
         */
        @JvmStatic
        fun llmCall(runId: String, modelName: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.LLM_CALL, "llm:$modelName")

        /**
         * Creates a planning observation context.
         */
        @JvmStatic
        fun planning(runId: String, planningPhase: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.PLANNING, "planning:$planningPhase")

        /**
         * Creates a state transition observation context.
         */
        @JvmStatic
        fun stateTransition(runId: String, stateName: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.STATE_TRANSITION, "state:$stateName")

        /**
         * Creates a lifecycle state observation context.
         */
        @JvmStatic
        fun lifecycle(runId: String, lifecycleState: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.LIFECYCLE, "lifecycle:$lifecycleState")

        /**
         * Creates a goal achievement observation context.
         */
        @JvmStatic
        fun goal(runId: String, goalName: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.GOAL, "goal:$goalName")

        /**
         * Creates a RAG operation observation context.
         */
        @JvmStatic
        fun rag(runId: String, ragOperation: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.RAG, "rag:$ragOperation")

        /**
         * Creates a ranking/selection observation context.
         */
        @JvmStatic
        fun ranking(rankingType: String): EasyAiObservationContext =
            EasyAiObservationContext("", EventType.RANKING, "ranking:$rankingType")

        /**
         * Creates a dynamic agent creation observation context.
         */
        @JvmStatic
        fun dynamicAgentCreation(agentName: String): EasyAiObservationContext =
            EasyAiObservationContext("", EventType.DYNAMIC_AGENT_CREATION, "dynamic_agent:$agentName")

        /**
         * Creates a custom operation observation context.
         */
        @JvmStatic
        fun custom(runId: String, operationName: String): EasyAiObservationContext =
            EasyAiObservationContext(runId, EventType.CUSTOM, operationName)
    }
}