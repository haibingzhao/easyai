package com.easy.easyai.web.model

import com.easy.easyai.core.model.ContentBlock
import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageSnapshot(
    val id: String? = null,
    val role: String,
    val content: List<ContentBlock>,
    val timestamp: Long,
    val stopReason: String? = null,
    val metadata: Map<String, String>? = null,
    val usage: UsageSnapshot? = null,
    val compactedAt: Long? = null,
    val parentMessageId: String? = null,
    val parentToolCallId: String? = null,
    val references: ChatStreamEvent.ReferencesSnapshot? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UsageSnapshot(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val cacheReadTokens: Int = 0,
    val cacheWriteTokens: Int = 0,
    val durationMs: Long = 0,
    val modelName: String? = null
)
