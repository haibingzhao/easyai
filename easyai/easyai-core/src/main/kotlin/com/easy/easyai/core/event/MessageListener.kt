package com.easy.easyai.core.event

import com.easy.easyai.core.model.EasyAiMessage

/**
 * Fields that can be selectively updated in message update operations.
 * When null is passed, all fields are updated (including bumping session contentUpdatedAt).
 * When a non-null set is passed, only the specified fields are written (no contentUpdatedAt bump).
 */
enum class MessageUpdateField {
    CONTENT_BLOCKS,
    METADATA,
    TOKEN_COUNTS,
    STOP_REASON
}

/**
 * Listener interface for message lifecycle events.
 * Implementations can persist messages to database, emit metrics, etc.
 *
 * This is invoked after each message is added to the transcript in the AgentLoop,
 * enabling real-time message persistence (as opposed to batch saving at stream end).
 */
interface MessageListener {
    /**
     * Called after messages are added to the transcript.
     *
     * @param messages The newly added messages
     */
    suspend fun onMessageAdded(messages: List<EasyAiMessage>)

    /**
     * Called when an existing message is updated in the transcript.
     * Used for scenarios like removing skipped placeholder entries from ToolResultMessages
     * after permission approval and tool re-execution.
     *
     * @param messageId The ID of the updated message
     * @param message The updated message content
     * @param fields The set of fields to update, or null to update all fields.
     *               When non-null, only the specified fields are written.
     */
    suspend fun onMessageUpdated(messageId: String, message: EasyAiMessage, fields: Set<MessageUpdateField>? = null) {
        // Default: no-op. Implementations with persistent storage should override.
    }
}