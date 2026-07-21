package com.easy.easyai.web.model

/**
 * Dual-dimension streaming status returned by [ChatStreamService.isSessionStreaming].
 *
 * @property local True when this server instance holds an active SSE connection for the session.
 * @property remote True when the DB status column is "streaming" (persisted across instances).
 */
data class StreamingStatus(
    val local: Boolean,
    val remote: Boolean
)
