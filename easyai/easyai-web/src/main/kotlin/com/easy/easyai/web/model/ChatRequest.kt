package com.easy.easyai.web.model

import com.easy.easyai.api.model.ModelOverrides
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
     * If not provided, the session runs in a per-session temporary workspace under
     * `{easyai.data-dir}/.temp-workspaces/{userId}/{sessionId}` instead of an unscoped directory.
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

    /** Structured input data for agents with input schema. Validated against agent's inputSchema. */
    val inputData: Map<String, Any?>? = null,

    /**
     * File attachments (images, text files) sent with the user message.
     * Images are forwarded to the LLM as Media objects; text files are inlined into the message.
     */
    val attachments: List<ChatAttachment>? = null,

    /**
     * Composer overrides for this run's effort and working context window. Null means "no overrides
     * for this run": the stored model config applies unchanged. An empty object clears any overrides
     * recorded on the session. Recorded overrides are replayed when a session is resumed, not by this
     * request path. The stored model config is never modified.
     */
    val modelOverrides: ModelOverrides? = null,

    /**
     * IANA time zone of the browser (e.g. `Asia/Shanghai`). Background work created from this turn —
     * watches — resolves "tomorrow 9am" against it instead of the server's default. Null falls back
     * to the server zone; a resumed turn does not inherit the zone of the request that opened the
     * session, and the watch result warns when the fallback was used.
     */
    val timezone: String? = null
)

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
    /** Local file path or stable storage reference. Null when [data] is provided. */
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