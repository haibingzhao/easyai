package com.easy.easyai.core.agent

/**
 * Per-owner Chat surface preferences: which agent a fresh chat starts with and which selectable
 * agents stay out of the composer's agent picker.
 *
 * One row addresses one owner bucket (`user_id`). The effective value is the first row across the
 * caller's ordered owner set (self → group; this table never folds in the shared `system` layer),
 * so a personal row shadows the group's and a group row applies to every member that has none.
 * An absent row means the built-in defaults:
 * no default override (the seeded `default-agent` wins when selectable) and nothing hidden.
 */
data class ChatPreference(
    val userId: String,
    val defaultAgentId: String? = null,
    val hiddenAgentIds: List<String> = emptyList(),
    val updatedAt: Long = 0
)

/**
 * Persistence for [ChatPreference] — one row per owner bucket, mirroring the
 * `aux_model_settings` group-aware precedent. The interface lives in `easyai-core` (implemented in
 * `easyai-repository`) so web controllers can reach it without a repository dependency.
 */
interface ChatPreferenceStore {

    /** The owner bucket's stored preference; null when it never configured one. */
    suspend fun get(userId: String): ChatPreference?

    /** Insert or replace the owner bucket's row. */
    suspend fun save(preference: ChatPreference)

    /** Drop the owner bucket's row, returning it to the built-in defaults; false when absent. */
    suspend fun delete(userId: String): Boolean

    /** The effective preference across [owners]; the first owner (self → group) with a row wins. */
    suspend fun getEffective(owners: Collection<String>): ChatPreference? {
        val seen = HashSet<String>()
        for (owner in owners) {
            if (owner.isBlank() || !seen.add(owner)) continue
            get(owner)?.let { return it }
        }
        return null
    }
}
