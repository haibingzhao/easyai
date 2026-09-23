package com.easy.easyai.core.message

import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.SystemMessage
import com.easy.easyai.core.model.UserMessage

/**
 * Projects server-prepared command snapshots for model input, estimation and summarization.
 * Authorization and expansion happen upstream; this layer never resolves commands or skills.
 * The returned list is ephemeral and must not be written back to the transcript or persisted.
 */
object CommandMessageProjection {

    @JvmStatic
    fun project(messages: List<EasyAiMessage>): List<EasyAiMessage> {
        val projected = ArrayList<EasyAiMessage>(messages.size)
        for (message in messages) {
            val expansion = (message as? UserMessage)
                ?.takeUnless { it.metadata["isCompactionSummary"] == "true" }
                ?.metadata?.get(UserMessage.COMMAND_EXPANSION)
            if (!expansion.isNullOrBlank()) {
                val expansionId = "command_expansion_${message.id}"
                val previous = projected.lastOrNull()
                // A summary agent can pass an already projected snapshot through preparePrompt.
                // Match our own adjacent projection, never an unrelated system message's text.
                if (previous !is SystemMessage || previous.id != expansionId || previous.text != expansion) {
                    projected.add(SystemMessage(id = expansionId, text = expansion))
                }
            }
            projected.add(message)
        }
        return projected
    }
}
