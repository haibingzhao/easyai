package com.easy.easyai.repository.session

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.event.MessageUpdateField
import com.easy.easyai.core.model.EasyAiMessage
import org.slf4j.LoggerFactory

/**
 * R2DBC-based implementation of [MessageListener].
 * Persists messages to the database in real-time via [R2dbcAsyncSessionStore].
 *
 * This enables incremental message persistence as each message is added to the transcript,
 * rather than batch saving at the end of the conversation.
 *
 * Note: upsertMessages() uses Exposed's upsert for idempotent inserts.
 */
class R2dbcMessageListener(
    private val store: R2dbcAsyncSessionStore,
    private val sessionId: String,
    private val context: AgentContext,
    private val parentMessageId: String? = null,
    private val parentToolCallId: String? = null
) : MessageListener {
    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Guards one-time session-row creation. Team member sessions are created lazily
     * (no session row exists until the first message is persisted), so we ensure the
     * row exists on the first write. Idempotent + cheap for sessions that already exist.
     */
    @Volatile
    private var sessionEnsured = false

    override suspend fun onMessageAdded(messages: List<EasyAiMessage>) {
        if (!sessionEnsured) {
            store.ensureSessionExists(sessionId, context)
            sessionEnsured = true
        }
        store.upsertMessages(context, sessionId, messages, parentMessageId, parentToolCallId)
        logger.debug("Persisted {} messages for session {}", messages.size, sessionId)
    }

    override suspend fun onMessageUpdated(messageId: String, message: EasyAiMessage, fields: Set<MessageUpdateField>?) {
        store.updateMessage(sessionId, messageId, message, fields)
        logger.debug("Updated message {} in session {}", messageId, sessionId)
    }
}