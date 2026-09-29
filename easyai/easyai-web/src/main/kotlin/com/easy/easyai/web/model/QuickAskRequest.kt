package com.easy.easyai.web.model

/**
 * Request for the stateless side-panel Q&A endpoint (POST /api/chat/quick-ask).
 * Nothing is persisted: no session row, no message rows.
 */
data class QuickAskRequest(
    val modelConfigId: String,
    val question: String,
    /** Text selected from a main-chat message, used as read-only context. */
    val selectedText: String,
    /** Previous turns of the side conversation (role: user|assistant). */
    val history: List<LlmMessage> = emptyList(),
)
