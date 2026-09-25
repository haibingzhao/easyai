package com.easy.easyai.core.agent

import com.easy.easyai.core.model.ToolCallContent
import com.easy.easyai.core.tool.ToolCallResult

internal class ReInvocationDetector {

    private var previousTurn = emptyMap<Key, Streak>()

    fun observeTurn(toolCalls: List<ToolCallContent>, results: List<ToolCallResult>): List<Notice> {
        val resultsById = results.associateBy { it.toolCallId }
        val current = previousTurn.toMutableMap()
        val seen = mutableSetOf<Key>()
        for ((position, call) in toolCalls.withIndex()) {
            val key = Key(call.name, call.arguments)
            seen.add(key)
            val result = resultsById[call.id]
            if (result == null || result.needPause || result.isSkipped) {
                current.remove(key)
                continue
            }
            val outcome = Outcome(result.resultText, result.isError)
            val previous = current[key]
            val count = if (previous != null && previous.outcome == outcome) previous.count + 1 else 1
            current[key] = Streak(outcome, count, call.id, position)
        }
        current.keys.retainAll(seen)
        previousTurn = current
        return current.entries
            .filter { it.value.count >= WARN_THRESHOLD }
            .sortedBy { it.value.position }
            .map { (key, streak) ->
                Notice(
                    toolName = key.name,
                    toolCallId = streak.toolCallId,
                    count = streak.count,
                    level = if (streak.count >= ESCALATE_THRESHOLD) Level.ESCALATE else Level.WARN
                )
            }
    }

    data class Notice(
        val toolName: String,
        val toolCallId: String,
        val count: Int,
        val level: Level
    )

    enum class Level { WARN, ESCALATE }

    private data class Key(val name: String, val arguments: String)

    private data class Outcome(val resultText: String, val isError: Boolean)

    private data class Streak(
        val outcome: Outcome,
        val count: Int,
        val toolCallId: String,
        val position: Int
    )

    private companion object {
        const val WARN_THRESHOLD = 2
        const val ESCALATE_THRESHOLD = 3
    }
}
