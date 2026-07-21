package com.easy.easyai.web.handler

import com.easy.easyai.web.model.ChatStreamEvent

/**
 * Converts goal status change notifications into SSE [ChatStreamEvent.GoalStatus] events.
 *
 * Works in tandem with [com.easy.easyai.core.goal.GoalStatusNotifier]:
 * - GoalCompletionCheck calls notifier on each state transition
 * - ChatStreamService subscribes a listener that pushes CustomEvents to the agent event stream
 * - This converter transforms those CustomEvents into ChatStreamEvent.GoalStatus for SSE
 */
class GoalStatusCustomEventConverter : CustomEventConverter {

    override val customType: String get() = "goal_status"

    override fun convert(event: com.easy.easyai.core.event.CustomEvent): List<ChatStreamEvent> {
        val objective = event.metadata["objective"] as? String ?: return emptyList()
        val status = event.metadata["status"] as? String ?: return emptyList()
        return listOf(
            ChatStreamEvent.GoalStatus(
                sessionId = event.sessionId,
                objective = objective,
                status = status,
                turnCount = (event.metadata["turnCount"] as? Number)?.toInt() ?: 0,
                maxTurns = (event.metadata["maxTurns"] as? Number)?.toInt() ?: 0,
                elapsedSeconds = (event.metadata["elapsedSeconds"] as? Number)?.toLong() ?: 0,
                evidence = event.metadata["evidence"] as? String,
                blockedReason = event.metadata["blockedReason"] as? String
            )
        )
    }
}
