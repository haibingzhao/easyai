package com.easy.easyai.compaction.model

/**
 * Represents the range of messages that will be compacted.
 *
 * @param messageIds IDs of messages that are being compacted
 * @param estimatedTokensBefore Estimated token count before compaction
 * @param userRoleCount Number of user messages in the compacted range
 * @param assistantRoleCount Number of assistant messages in the compacted range
 */
data class CompactedRange(
    val messageIds: List<String>,
    val estimatedTokensBefore: Int,
    val userRoleCount: Int = 0,
    val assistantRoleCount: Int = 0
) {
    val messageCount: Int get() = messageIds.size
}