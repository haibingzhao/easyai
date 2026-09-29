package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/** Request to fork a session: copies history up to and including [messageId]. */
data class ForkSessionRequest(
    val messageId: String
)

/** One fork branch entry for the Summary panel branch list. */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ForkBranchInfo(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    /** Direct source session of this fork (root session id when forked from the main session). */
    val forkedFromSessionId: String
)
