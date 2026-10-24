package com.easy.easyai.web.context

import com.easy.easyai.core.agent.AgentContext

/**
 * Host extension point that supplies the initial tags for a freshly opened chat session.
 *
 * Called once, on the first user turn of a session (when no history exists yet), after the
 * session row has been created. Hosts use it to label sessions by business scenario so the
 * History list can group and filter them without the user tagging every conversation by hand.
 *
 * Contract:
 * - Tags are persisted with the session owner's user id; implementers never choose the owner.
 * - Values are normalized by [com.easy.easyai.repository.session.AsyncSessionStore.updateTags]
 *   (trimmed, de-duplicated, length/count capped).
 * - Returning an empty list leaves the session untagged. A failing provider must be logged by
 *   the caller and must never fail the chat turn.
 * - Only fresh sessions are tagged; resumed turns are skipped so user edits are not overwritten.
 */
fun interface ChatSessionTagProvider {
    suspend fun tags(context: AgentContext): List<String>
}
