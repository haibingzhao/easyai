package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Request DTO for chat endpoint.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatRequest(
    /**
     * Optional session ID for multi-turn conversations.
     * If not provided, a new session will be created.
     */
    val sessionId: String? = null,

    /**
     * Optional project ID for multi-project isolation.
     * If not provided, the session will not be associated with any project.
     */
    val projectId: String? = null,

    /**
     * User message to send to the agent.
     */
    val message: String? = null,

    /**
     * Agent ID to use for this request.
     * Determines the system prompt, tools, and agent mode.
     * Default: "default-agent".
     */
    val agentId: String = "default-agent",

    /**
     * Model provider configuration ID.
     * Used to determine which model and protocol to use.
     */
    val modelProviderConfigId: String? = null,

    /**
     * Optional model override for this request.
     */
    val model: String? = null,

    /**
     * Chat options.
     */
    val options: ChatOptions? = null,

    /** Structured input data for agents with input schema. Validated against agent's inputSchema. */
    val inputData: Map<String, Any?>? = null,

    /**
     * File attachments (images, text files) sent with the user message.
     * Images are forwarded to the LLM as Media objects; text files are inlined into the message.
     */
    val attachments: List<ChatAttachment>? = null
) {
    @JsonInclude(JsonInclude.Include.NON_NULL)
    data class ChatOptions(
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        val thinking: Boolean? = null
    )
}

/**
 * File attachment sent from the frontend.
 *
 * Two modes:
 * - **Base64 mode** (clipboard images): [data] contains base64-encoded content, [filePath] is null.
 * - **Path mode** (local files): [filePath] is the absolute path, [data] is null.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ChatAttachment(
    val name: String,
    val mimeType: String,
    /** Base64-encoded content (no data URL prefix). Null when [filePath] is provided. */
    val data: String? = null,
    /** Absolute path to a local file. Null when [data] is provided. */
    val filePath: String? = null
)

/**
 * Response for session creation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessionResponse(
    val sessionId: String,
    val message: String? = null
)