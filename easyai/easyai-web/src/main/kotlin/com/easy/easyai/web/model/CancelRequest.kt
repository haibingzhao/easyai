package com.easy.easyai.web.model

/**
 * Request model for cancelling a chat session.
 */
data class CancelRequest(
    val sessionId: String
)