package com.easy.easyai.core.team

import com.easy.easyai.core.model.EasyAiMessage

/**
 * Loads persisted message history for a member session.
 *
 * Used by Team Agent's resume_member tool to restore a blocked member's
 * conversation before continuing execution with the leader's resolution.
 *
 * Implementations are wired in the autoconfigure layer, bridging
 * easyai-core (where this interface lives) and easyai-repository
 * (where [com.easy.easyai.core.model.EasyAiMessage] persistence resides).
 */
fun interface TeamMemberHistoryLoader {
    /**
     * Load active (non-compacted) messages for the given member session.
     *
     * @param sessionId The member's session ID (from [BlockedMemberState.sessionId]).
     * @return Message history in chronological order, or empty list if unavailable.
     */
    suspend fun loadActiveMessages(sessionId: String): List<EasyAiMessage>
}
