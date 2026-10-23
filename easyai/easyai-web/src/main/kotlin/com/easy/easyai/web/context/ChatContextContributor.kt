package com.easy.easyai.web.context

import org.springframework.http.HttpHeaders

/**
 * The server-resolved identity of one chat request, handed to [ChatContextContributor]s.
 *
 * Everything here comes from the authenticated request (JWT subject, group claims, HTTP headers),
 * never from [com.easy.easyai.web.model.ChatRequest]. Headers are a read-only snapshot with
 * single values and lower-cased names; `authorization` / `cookie` are stripped because a
 * contributor never needs them to resolve its own product identity.
 *
 * @param userId the authenticated caller (`getCurrentUserId()`); `system` when auth is disabled.
 * @param owners the caller's read-visibility owner set (`{self, group bucket, system}`).
 * @param sessionId the session being started or resumed; null only before one is minted.
 * @param groupId the group this login was minted under, or null for a group-less token.
 * @param headers request headers, lower-cased single values, minus credentials.
 */
data class ChatTurnContext(
    val userId: String,
    val owners: Set<String>,
    val sessionId: String?,
    val groupId: String?,
    val headers: Map<String, String>
) {
    companion object {
        private val CREDENTIAL_HEADERS = setOf("authorization", "cookie", "set-cookie")

        /** Snapshot the request headers into the map shape contributors receive. */
        @JvmStatic
        fun from(
            userId: String,
            owners: Set<String>,
            sessionId: String?,
            groupId: String?,
            headers: HttpHeaders
        ): ChatTurnContext {
            val snapshot = LinkedHashMap<String, String>()
            headers.forEach { name, values ->
                val key = name.lowercase()
                if (key !in CREDENTIAL_HEADERS && values.isNotEmpty()) snapshot[key] = values.first()
            }
            return ChatTurnContext(userId, owners, sessionId, groupId, snapshot)
        }
    }
}

/**
 * Host extension point for server-trusted session variables on a chat run.
 *
 * Called by `ChatStreamService` on every run entry — a fresh chat, resume, question/permission
 * answer, and manual compaction — with the identity of the authenticated request. The returned
 * key/values are merged into the run's `AgentContext.sessionVariables` **as trusted values**:
 * compaction and DB restoration cannot overwrite them, and the model never gets to supply them.
 *
 * Contract:
 * - Values must be derived from [ChatTurnContext] and the host's own tables, never echoed from
 *   client-supplied payloads without server-side validation.
 * - Multiple contributors are applied in bean order; a later contributor wins on a shared key.
 * - Throw [ChatContextRejectedException] to refuse the run (HTTP 403). Any other exception is
 *   treated as a backend failure and becomes an SSE error, so use the rejection type deliberately.
 * - Deployments without a contributor keep the pre-existing behaviour: no variables, no change.
 * - QuickAsk, the page-assistant endpoints and swarm runs do not invoke contributors; only the
 *   Chat entry points that build or restore an interactive session do.
 */
fun interface ChatContextContributor {
    suspend fun contribute(turn: ChatTurnContext): Map<String, String>
}

/**
 * Refuse a chat run because the request's server-resolved identity is not acceptable
 * (for example the active-family header disagrees with the token's group claim).
 *
 * Rendered as HTTP 403 with an `{ "error": ... }` body by `ApiExceptionHandler`; do not add
 * `@ResponseStatus` here, the advice's mapping is what keeps the message readable.
 */
class ChatContextRejectedException(message: String) : RuntimeException(message)
