package com.easy.easyai.autoconfigure.core

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.model.aux.ResolvedAuxModel
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Default [AuxModelResolver]: reads a user's per-task choice from [AuxModelSettingsStore], resolves
 * the referenced `ModelProviderConfig` and builds its [org.springframework.ai.chat.model.ChatModel]
 * via the matching [ChatModelFactory].
 *
 * Every miss — no store (persistence off), unconfigured task, blank/null user, deleted config, or
 * no factory for the protocol — returns null so the caller keeps its own default. Successful builds
 * are cached per `(userId, task)`; [refresh] drops one entry so a saved choice applies live.
 */
class DefaultAuxModelResolver(
    private val settingsStore: AuxModelSettingsStore?,
    private val configStore: ModelProviderConfigStore?,
    private val modelFactories: List<ChatModelFactory>
) : AuxModelResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val cache = ConcurrentHashMap<String, ResolvedAuxModel>()

    override suspend fun resolve(userId: String?, task: AuxModelTask): ResolvedAuxModel? {
        val store = settingsStore ?: return null
        val configs = configStore ?: return null
        val owner = userId ?: return null

        val cacheKey = cacheKey(owner, task)
        cache[cacheKey]?.let { return it }

        val settings = store.get(owner, task) ?: return null
        val modelConfigId = settings.modelConfigId
        if (modelConfigId.isBlank()) return null

        val config = configs.getConfig(modelConfigId, owner)
        if (config == null) {
            logger.warn("Aux model config '{}' not found for task '{}' (user '{}'); using default", modelConfigId, task.key, owner)
            return null
        }

        val factory = modelFactories.firstOrNull { it.supports(config.protocol) }
        if (factory == null) {
            logger.warn("No ChatModelFactory for protocol '{}' (aux task '{}'); using default", config.protocol, task.key)
            return null
        }

        val resolved = ResolvedAuxModel(chatModel = factory.create(config), modelConfig = config)
        cache[cacheKey] = resolved
        return resolved
    }

    override fun refresh(userId: String, task: AuxModelTask) {
        cache.remove(cacheKey(userId, task))
    }

    private fun cacheKey(userId: String, task: AuxModelTask): String = "$userId::${task.key}"
}
