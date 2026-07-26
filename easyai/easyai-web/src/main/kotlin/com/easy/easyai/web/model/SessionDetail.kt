package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class SessionDetail(
    val id: String,
    val title: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val messageCount: Int,
    val messages: List<MessageSnapshot>,
    val pendingPermission: Map<String, Any?>? = null,
    /** Why the last agent execution ended (e.g. "max_iterations"). null = normal/no value. */
    val endReason: String? = null,
    /** Agent ID from the last message — used by frontend to restore agent selection. */
    val lastAgentId: String? = null,
    /** Model config ID from the last message — used by frontend to restore model selection. */
    val lastConfigId: String? = null
)

/**
 * Per-file change info within a checkpoint.
 */
data class FileChangeInfo(
    val path: String,
    val additions: Int,
    val deletions: Int,
    val status: String,
    val changedBy: String? = null,
    /** Team member ID when the change was made by a member agent (null for leader/user changes). */
    val memberId: String? = null
)

/**
 * Summary of a checkpoint for loading historical session file changes.
 */
data class CheckpointSummary(
    val messageId: String?,
    val assistantMessageId: String?,
    val snapshotHash: String?,
    val filesChanged: List<FileChangeInfo>,
    val additions: Int,
    val deletions: Int,
    val createdAt: Long
)

/**
 * Response for incremental message fetching.
 * Contains only messages created after the given timestamp, plus metadata
 * that allows the frontend to decide whether to fall back to full reload.
 */
data class SessionMessagesAfterResponse(
    val sessionId: String,
    val messages: List<MessageSnapshot>,
    /** True if compaction occurred after [afterTimestamp] — historical messages may have changed. */
    val compactionOccurredAfter: Boolean,
    /** Session.contentUpdatedAt dirty marker — bumped only by updateMessage(), not by updateStatus(). */
    val contentUpdatedAt: Long,
    val streaming: Boolean,
    /** Pending permission request, if any. */
    val pendingPermission: Map<String, Any?>? = null,
    /** Why the last agent execution ended (e.g. "max_iterations"). null = normal/no value. */
    val endReason: String? = null
)
