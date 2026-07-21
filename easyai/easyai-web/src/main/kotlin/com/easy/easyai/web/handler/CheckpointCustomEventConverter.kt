package com.easy.easyai.web.handler

import com.easy.easyai.core.event.CustomEvent
import com.easy.easyai.snapshot.model.FileDiff
import com.easy.easyai.web.model.ChatStreamEvent
import com.easy.easyai.web.model.FileChangeInfo

/**
 * Converts checkpoint [CustomEvent]s to [ChatStreamEvent.Checkpoint] SSE events.
 *
 * Expected metadata keys:
 * - `messageId` (String?, optional) — the user message ID associated with the checkpoint (null for tool-level checkpoints)
 * - `assistantMessageId` (String?, optional) — the assistant message ID for frontend association
 * - `snapshotHash` (String?, optional) — the Git commit hash of the checkpoint (null for tool-level checkpoints)
 * - `filesChanged` (List<FileDiff>, optional) — list of changed file diffs with path, status, additions, deletions
 * - `additions` (Number, optional) — total line additions
 * - `deletions` (Number, optional) — total line deletions
 */
class CheckpointCustomEventConverter : CustomEventConverter {

    override val customType: String get() = "checkpoint"

    @Suppress("UNCHECKED_CAST")
    override fun convert(event: CustomEvent): List<ChatStreamEvent> {
        val messageId = event.metadata["messageId"] as? String
        val assistantMessageId = event.metadata["assistantMessageId"] as? String
        // At least one of messageId or assistantMessageId must be present
        if (messageId == null && assistantMessageId == null) return emptyList()
        val rawFiles = event.metadata["filesChanged"] as? List<FileDiff> ?: emptyList()
        val filesChanged = rawFiles.map { diff ->
            FileChangeInfo(
                path = diff.path,
                additions = diff.additions,
                deletions = diff.deletions,
                status = diff.status.name.lowercase()
            )
        }
        return listOf(
            ChatStreamEvent.Checkpoint(
                messageId = messageId,
                assistantMessageId = assistantMessageId,
                snapshotHash = event.metadata["snapshotHash"] as? String,
                filesChanged = filesChanged,
                additions = (event.metadata["additions"] as? Number)?.toInt() ?: 0,
                deletions = (event.metadata["deletions"] as? Number)?.toInt() ?: 0
            )
        )
    }
}
