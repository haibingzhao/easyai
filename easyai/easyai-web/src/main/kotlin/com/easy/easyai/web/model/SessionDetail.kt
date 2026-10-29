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
    val lastConfigId: String? = null,
    /** Context window (tokens) of the last message's model — used by frontend token bar for the real percentage. */
    val modelContextLength: Int? = null,
    /** Session-scoped variables (key -> value) persisted for this session, for frontend display. */
    val variables: Map<String, String>? = null,
    /** Direct source session when this session was created by forking (null = main session). */
    val forkedFromSessionId: String? = null,
    /** Root main session this fork descends from (null = main session). */
    val forkRootSessionId: String? = null,
    /**
     * Whether the snapshot/checkpoint system is active for this session's project.
     * False when no SnapshotService bean is present, the session has no project path,
     * or the service reports the project disabled (e.g. a no-op override).
     * The frontend hides the file-review UI when this is false.
     */
    val snapshotEnabled: Boolean = false,
    /**
     * Project the session runs in, as resolved by the backend. [workspaceKind] is "temp" for a
     * system-managed per-session scratch workspace, which the frontend shows instead of a project
     * name and excludes from project-scoped features such as PROJECT memory.
     */
    val workspaceProjectId: String? = null,
    val workspacePath: String? = null,
    val workspaceKind: String? = null,
    /** Tags attached to this session (empty when none). */
    val tags: List<String> = emptyList(),
    /** Seq of the newest message in this response — the cursor for incremental polling. */
    val newestMessageSeq: Long? = null,
    /**
     * History fingerprint. Changes only when a rewrite happened (edit, delete, truncation,
     * compaction, revert); appends move [newestMessageSeq] instead. A cached transcript may only
     * be stitched with a delta that reports the same revision.
     */
    val contentRevision: Long? = null
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
    val createdAt: Long,
    /**
     * false when this entry's diff or author attribution failed, so [filesChanged] cannot be
     * trusted. The frontend must treat such an entry as "rollback scope unknown" and refuse to
     * let an edit skip its file-rollback confirmation — an empty list here does NOT mean
     * "no files changed".
     */
    val diffsComplete: Boolean = true
)

/**
 * Result of the checkpoint listing.
 *
 * Previously the endpoint returned a bare list and collapsed five distinct outcomes
 * (no snapshot service / no context / snapshots disabled / listing threw / genuinely empty)
 * into `[]`. The frontend then marked all of them "loaded, no files to roll back" and edited
 * without confirmation. [status] keeps them apart.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CheckpointListResponse(
    /** `ok` = query succeeded and the list is complete; `disabled` = snapshots off; `failed` = error. */
    val status: String,
    val checkpoints: List<CheckpointSummary> = emptyList(),
    val error: String? = null,
) {
    companion object {
        const val STATUS_OK = "ok"
        const val STATUS_DISABLED = "disabled"
        const val STATUS_FAILED = "failed"

        fun ok(checkpoints: List<CheckpointSummary>) = CheckpointListResponse(STATUS_OK, checkpoints)
        fun disabled() = CheckpointListResponse(STATUS_DISABLED)
        fun failed(error: String?) = CheckpointListResponse(STATUS_FAILED, emptyList(), error)
    }
}

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
    val endReason: String? = null,
    /** Seq of the newest row in this response — the cursor for the next incremental call. */
    val newestSeq: Long? = null,
    /**
     * History revision at read time. A client only appends these messages to its cached
     * transcript when this matches the revision it cached.
     */
    val revision: Long? = null
)
