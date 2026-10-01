package com.easy.easyai.web.handler

import com.easy.easyai.core.event.CustomEvent
import com.easy.easyai.web.model.ChatStreamEvent

/**
 * Converts background task lifecycle notifications into SSE [ChatStreamEvent.BackgroundTaskEvent] events.
 *
 * Works with [com.easy.easyai.tools.background.BackgroundTaskManager]:
 * - BackgroundTaskManager publishes CustomEvents on task state transitions
 * - This converter transforms those CustomEvents into ChatStreamEvent.BackgroundTaskEvent for SSE
 */
class BackgroundTaskCustomEventConverter : CustomEventConverter {

    override val customType: String get() = "background_task"

    override fun convert(event: CustomEvent): List<ChatStreamEvent> {
        val eventType = event.metadata["event"] as? String ?: return emptyList()
        val taskId = event.metadata["taskId"] as? String ?: return emptyList()

        return listOf(
            ChatStreamEvent.BackgroundTaskEvent(
                event = eventType,
                taskId = taskId,
                toolName = event.metadata["toolName"] as? String,
                message = event.metadata["message"] as? String,
                result = event.metadata["result"] as? String,
                error = event.metadata["error"] as? String,
                durationMs = (event.metadata["durationMs"] as? Number)?.toLong()
            )
        )
    }
}
