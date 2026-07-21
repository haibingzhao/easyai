package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Request model for resuming a cancelled or errored chat session.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ResumeRequest(
    /** Session ID to resume. */
    val sessionId: String,
    /** Optional user message to add before resuming. */
    val message: String? = null
)
