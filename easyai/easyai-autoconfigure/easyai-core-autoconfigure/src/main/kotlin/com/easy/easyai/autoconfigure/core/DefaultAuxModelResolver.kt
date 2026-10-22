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
 * system) and take the first owner that has a choice; the referenced config is then resolved
 * across that set plus the shared `system` layer, so a personal choice may point at a group- or
 * system-shared model. The single-id forms delegate with just `{userId}` — their choice lookup
 * stays owner-only, while the config they reference still falls back to `system`, matching the
 * store's single-id semantics and the settings page's write validation.
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

        val settings = firstChoice(key.owners, task) ?: return null
        val modelConfigId = settings.modelConfigId
        if (modelConfigId.isBlank()) return null

        val config = configFor(modelConfigId, key.owners, task) ?: return null
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

        val settings = firstChoice(key.owners, task) ?: return null
        val modelConfigId = settings.modelConfigId
        if (modelConfigId.isBlank()) return null

        val config = configFor(modelConfigId, key.owners, task) ?: return null
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

    /** The first owner in priority order that has a non-blank choice for [task]. */
    private suspend fun firstChoice(owners: List<String>, task: AuxModelTask): AuxModelSettings? {
        val store = settingsStore ?: return null
        for (owner in owners) {
            val settings = store.get(owner, task) ?: continue
            if (settings.modelConfigId.isNotBlank()) return settings
        }
        return null
    }

    /**
     * Resolve the referenced config across the caller's whole visibility set plus the shared
     * `system` layer. The owner that supplied the choice only decides *which* row won; it does not
     * narrow what that row may reference.
     *
     * The aux *choice* never folds in `system` (see [AuxModelSettingsStore]), but the model config
     * it points at is a shared asset: the settings page lists and validates choices against
     * `currentOwners()` (self + group + system). Without `system` here a system-owned model could
     * be saved yet never resolve at runtime — ASR/refine would answer 409, curation would report
     * unavailable and compaction would silently fall back to the session model.
     */
    private suspend fun configFor(modelConfigId: String, owners: List<String>, task: AuxModelTask): ModelProviderConfig? {
        val configs = configStore ?: return null
        val lookupOwners = (owners + SYSTEM_OWNER).distinct()
        val config = configs.getConfig(modelConfigId, lookupOwners)
        if (config == null) {
            logger.warn("Aux model config '{}' not found for task '{}' (owners '{}'); using default", modelConfigId, task.key, lookupOwners)
        }
        return config
    }

    private fun keyOf(owners: Collection<String>, task: AuxModelTask): Key? {
        val ordered = owners.filter { it.isNotBlank() }.distinct()
        return if (ordered.isEmpty()) null else Key(ordered, task)
    }

    private companion object {
        /** Mirrors `UserScope.SYSTEM_USER_ID`; this module has no dependency on the repository constant. */
        const val SYSTEM_OWNER = "system"
    }
}
