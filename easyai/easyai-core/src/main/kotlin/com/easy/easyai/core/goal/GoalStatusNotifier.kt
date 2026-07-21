package com.easy.easyai.core.goal

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Callback interface for receiving goal status change notifications.
 *
 * Used by the web layer to bridge GoalCompletionCheck state changes
 * into the SSE event stream, so the frontend can display goal progress in real-time.
 */
fun interface GoalStatusListener {
    fun onGoalStatusChanged(goal: GoalState)
}

/**
 * Thread-safe pub/sub for goal status changes.
 *
 * [GoalCompletionCheck] calls [notifyGoalChanged] after each state transition;
 * the web layer subscribes via [addListener] / [removeListener] to fan-out
 * [ChatStreamEvent.GoalStatus] events into the SSE stream.
 */
class GoalStatusNotifier {
    private val listeners = CopyOnWriteArrayList<GoalStatusListener>()

    fun addListener(listener: GoalStatusListener) {
        listeners.add(listener)
    }

    fun removeListener(listener: GoalStatusListener) {
        listeners.remove(listener)
    }

    fun notifyGoalChanged(goal: GoalState) {
        for (listener in listeners) {
            try {
                listener.onGoalStatusChanged(goal)
            } catch (_: Exception) { /* best-effort notification */ }
        }
    }
}
