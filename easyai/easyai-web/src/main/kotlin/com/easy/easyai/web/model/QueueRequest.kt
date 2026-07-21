package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Request body for adding a message to the session queue (steer or followUp).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueueMessageRequest(
    /** Message content text. */
    val content: String,
    /** Queue type: "steer" or "followUp". */
    val type: String,
    /** Optional image attachments for the queued message. */
    val attachments: List<ChatAttachment>? = null
)

/**
 * Request body for reordering queued messages.
 */
data class QueueReorderRequest(
    /** Ordered list of queue IDs representing the new order. */
    val ids: List<String>
)

/**
 * Request body for updating a queued message's content.
 */
data class QueueUpdateRequest(
    /** Updated message content text. */
    val content: String
)

/**
 * DTO representing a queued message returned by the queue API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class QueuedMessageResponse(
    val id: String,
    val content: String,
    /** Queue type: "steer" or "followUp". */
    val type: String
)
