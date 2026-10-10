package com.easy.easyai.autoconfigure.core

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelSettings
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.model.aux.ResolvedAuxModel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [AuxModelResolver]: reads the per-task choice from [AuxModelSettingsStore], resolves the
 * referenced `ModelProviderConfig` and builds its [com.easy.easyai.api.llm.ChatModel] via
 * the matching [ChatModelFactory].
 *
 * Group sharing: the owners overloads walk the caller's visibility set in order (self → group →
 * system) and take the first owner that has a choice, then resolve that owner's referenced config
 * (which still folds in the shared `system` layer). The single-id forms delegate with just
 * `{userId}`, preserving the historical self-only behaviour for callers that carry no group claims.
 *
 * Every miss — no store (persistence off), unconfigured task, blank/null owner, deleted config, or no
 * factory for the protocol — returns null so the caller keeps its own default. Successful builds are
 * cached per `(owners, task)`; [refresh] drops every entry whose owner set contains the given id, so
 * one save by a group owner evicts the whole group's cached resolution.
 */
class DefaultAuxModelResolver(
    private val settingsStore: AuxModelSettingsStore?,
    private val configStore: ModelProviderConfigStore?,
    private val modelFactories: List<ChatModelFactory>
) : AuxModelResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Cache key: the exact owner list a resolution was computed for, plus the task. Ordered, not a
     * set — [firstChoice] picks the winner by position, so two callers passing the same owners in a
     * different priority order must not share an entry.
     */
    private data class Key(val owners: List<String>, val task: AuxModelTask)

    private val cache = ConcurrentHashMap<Key, ResolvedAuxModel>()
    private val configCache = ConcurrentHashMap<Key, ModelProviderConfig>()

    override suspend fun resolve(userId: String?, task: AuxModelTask): ResolvedAuxModel? {
        val owner = userId ?: return null
        return resolve(listOf(owner), task)
    }

    override suspend fun resolveConfig(userId: String?, task: AuxModelTask): ModelProviderConfig? {
        val owner = userId ?: return null
        return resolveConfig(listOf(owner), task)
    }

    override suspend fun resolve(owners: Collection<String>, task: AuxModelTask): ResolvedAuxModel? {
        val key = keyOf(owners, task) ?: return null
        cache[key]?.let { return it }

        val (settings, matchedOwner) = firstChoice(key.owners, task) ?: return null
        val modelConfigId = settings.modelConfigId
        if (modelConfigId.isBlank()) return null

        val config = configFor(modelConfigId, matchedOwner, task) ?: return null
        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
        if (factory == null) {
            logger.warn("No ChatModelFactory for protocol '{}' (aux task '{}'); using default", config.protocol, task.key)
            return null
        }

        val resolved = ResolvedAuxModel(chatModel = factory.create(config), modelConfig = config)
        cache[key] = resolved
        return resolved
    }

    override suspend fun resolveConfig(owners: Collection<String>, task: AuxModelTask): ModelProviderConfig? {
        val key = keyOf(owners, task) ?: return null
        configCache[key]?.let { return it }

        val (settings, matchedOwner) = firstChoice(key.owners, task) ?: return null
        val modelConfigId = settings.modelConfigId
        if (modelConfigId.isBlank()) return null

        val config = configFor(modelConfigId, matchedOwner, task) ?: return null
        configCache[key] = config
        return config
    }

    override fun refresh(userId: String, task: AuxModelTask) {
        // Evict every cached resolution computed for an owner set that includes this id: a personal
        // save clears the caller's entry; a group-owner save clears every member who resolved through
        // that group bucket.
        cache.keys.filter { it.task == task && userId in it.owners }.forEach { cache.remove(it) }
        configCache.keys.filter { it.task == task && userId in it.owners }.forEach { configCache.remove(it) }
    }

    /** The first owner in priority order that has a non-blank choice for [task], with that owner id. */
    private suspend fun firstChoice(owners: List<String>, task: AuxModelTask): Pair<AuxModelSettings, String>? {
        val store = settingsStore ?: return null
        for (owner in owners) {
            val settings = store.get(owner, task) ?: continue
            if (settings.modelConfigId.isNotBlank()) return settings to owner
        }
        return null
    }

    /** Resolve the referenced config within the matched owner's bucket (which folds in `system`). */
    private suspend fun configFor(modelConfigId: String, matchedOwner: String, task: AuxModelTask): ModelProviderConfig? {
        val configs = configStore ?: return null
        val config = configs.getConfig(modelConfigId, matchedOwner)
        if (config == null) {
            logger.warn("Aux model config '{}' not found for task '{}' (owner '{}'); using default", modelConfigId, task.key, matchedOwner)
        }
        return config
    }

    private fun keyOf(owners: Collection<String>, task: AuxModelTask): Key? {
        val ordered = owners.filter { it.isNotBlank() }.distinct()
        return if (ordered.isEmpty()) null else Key(ordered, task)
    }
}
