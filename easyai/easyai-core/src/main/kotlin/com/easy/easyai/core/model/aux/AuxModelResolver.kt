package com.easy.easyai.core.model.aux

import com.easy.easyai.api.llm.ChatModel
import com.easy.easyai.api.model.ModelProviderConfig

/**
 * A resolved auxiliary model: both the live [ChatModel] to call and the [ModelProviderConfig] it
 * was built from. Callers need the config too, not just the model, so downstream option building
 * (model name, protocol, thinking toggle) matches the configured provider rather than the session's.
 */
data class ResolvedAuxModel(
    val chatModel: ChatModel,
    val modelConfig: ModelProviderConfig
)

/**
 * Resolves the per-user configured model for an [AuxModelTask].
 *
 * Resolution returns null when the task is unconfigured, the referenced config no longer exists,
 * no factory supports its protocol, or persistence is unavailable — in every case the caller keeps
 * its own default (for compaction, the chat-session model). Implementations cache the built
 * [ChatModel]; [refresh] drops one entry so a saved choice takes effect without a restart.
 */
interface AuxModelResolver {

    /** The user's configured model for [task]; null means "not configured — use the default". */
    suspend fun resolve(userId: String?, task: AuxModelTask): ResolvedAuxModel?

    /**
     * The raw config row for [task] without building a ChatModel — for consumers that speak their
     * own protocol to the endpoint (e.g. System One skill routing) and only need apiKey/baseUrl/modelId.
     */
    suspend fun resolveConfig(userId: String?, task: AuxModelTask): ModelProviderConfig?

    /**
     * Group-aware [resolve]: the choice is read across [owners] (self → group, first hit wins) and
     * the referenced config is resolved against the same set. Aux choices never fold in the `system`
     * layer, so callers with an [com.easy.easyai.core.agent.AgentContext] pass `auxOwners` (not
     * `effectiveOwners`), matching the web boundary's `currentGroupOwners()`.
     *
     * One caller deliberately passes the wider set: skill routing resolves the `SKILL_SELECTION` model
     * with the same owner set it reads the skill catalog with, because the catalog must see `system`
     * skills and a deployment-wide selection model is a default every user of that deployment should
     * inherit. Compaction, curation and every other task follows the rule above.
     */
    suspend fun resolve(owners: Collection<String>, task: AuxModelTask): ResolvedAuxModel?

    /** Group-aware [resolveConfig]; see [resolve] for the owner-set semantics. */
    suspend fun resolveConfig(owners: Collection<String>, task: AuxModelTask): ModelProviderConfig?

    /**
     * Invalidate every cached entry whose owner set contains [userId] for [task], so the next
     * [resolve] re-reads config. Passing a group bucket id evicts all members who cached a resolution
     * through that group — one save by the group owner takes effect for the whole group, no restart.
     */
    fun refresh(userId: String, task: AuxModelTask)
}
