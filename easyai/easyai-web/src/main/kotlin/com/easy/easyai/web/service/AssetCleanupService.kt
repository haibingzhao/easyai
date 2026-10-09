package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelConfigGroupStore
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.storage.StorageSettingsStore
import com.easy.easyai.skills.SkillRefreshService
import com.easy.easyai.tools.mcp.AsyncMcpServerStore
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

/**
 * Per-kind tally of what [AssetCleanupService.retireOwner] removed, so the caller (the product's
 * group-dissolution flow) can log or surface exactly what happened. A kind absent from the map was
 * either empty or backed by a store this deployment does not wire.
 */
data class AssetCleanupReport(
    val owner: String,
    val removed: Map<String, Int>,
    val failures: List<String>
) {
    val totalRemoved: Int get() = removed.values.sum()
}

/**
 * Cascade-deletes every asset owned by one bucket id, the framework side of group dissolution.
 *
 * The product owns the group/member tables; when a group is dissolved it calls [retireOwner] with the
 * group's hidden `groupUserId` and the framework removes everything stamped with that owner: the seven
 * A-class config tables (model provider configs, model config groups, aux model choices, MCP servers,
 * agents, skill catalog rows, storage settings), the group's knowledge slice, and each skill's install
 * directory plus its RAG slice (via [SkillRefreshService.deleteSkill]).
 *
 * Every kind is deleted independently and a failure in one is recorded, not thrown, so a partial
 * cleanup still removes everything it can; the report carries the failures for a retry pass. `system`
 * and blank ids are refused outright — retiring the shared bucket would wipe every deployment's defaults.
 *
 * This is an ordinary bean, not a callback SPI: the product invokes it directly (typically async) and
 * is responsible for scheduling retries off the [AssetCleanupReport.failures].
 */
@Service
class AssetCleanupService(
    @param:Autowired(required = false) private val modelConfigStore: ModelProviderConfigStore? = null,
    @param:Autowired(required = false) private val modelConfigGroupStore: ModelConfigGroupStore? = null,
    @param:Autowired(required = false) private val auxModelSettingsStore: AuxModelSettingsStore? = null,
    @param:Autowired(required = false) private val mcpServerStore: AsyncMcpServerStore? = null,
    @param:Autowired(required = false) private val agentStore: AsyncAgentStore? = null,
    @param:Autowired(required = false) private val skillCatalogStore: AsyncSkillCatalogStore? = null,
    @param:Autowired(required = false) private val storageSettingsStore: StorageSettingsStore? = null,
    @param:Autowired(required = false) private val knowledgeStore: KnowledgeStore? = null,
    @param:Autowired(required = false) private val skillRefreshService: SkillRefreshService? = null
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun retireOwner(owner: String): AssetCleanupReport {
        require(owner.isNotBlank()) { "owner must not be blank" }
        require(owner != AuthConstants.SYSTEM_USER_ID) {
            "refusing to retire the shared '${AuthConstants.SYSTEM_USER_ID}' bucket"
        }

        val removed = LinkedHashMap<String, Int>()
        val failures = mutableListOf<String>()
        logger.info("asset_cleanup start owner={}", owner)

        suspend fun cleanup(kind: String, block: suspend () -> Int) {
            try {
                val count = block()
                if (count > 0) removed[kind] = count
                logger.info("asset_cleanup kind={} owner={} removed={}", kind, owner, count)
            } catch (e: Exception) {
                failures.add("$kind: ${e.message ?: e.javaClass.simpleName}")
                logger.error("asset_cleanup kind={} owner={} failed", kind, owner, e)
            }
        }

        cleanup("model") {
            val store = modelConfigStore ?: return@cleanup 0
            // Every model type, not just CHAT: generation rows carry api keys too, and leaving them
            // behind would keep the retired bucket's credentials readable in the database.
            ModelType.entries.sumOf { type ->
                store.getModelConfigs(type, owner)
                    .filter { it.userId == owner }
                    .count { store.deleteConfig(it.id, owner) }
            }
        }
        cleanup("modelGroup") {
            val store = modelConfigGroupStore ?: return@cleanup 0
            val groups = store.getAllGroups(owner)
            groups.count { store.deleteGroup(it.id, owner) }
        }
        cleanup("aux") {
            val store = auxModelSettingsStore ?: return@cleanup 0
            val settings = store.getAll(owner)
            settings.count { s -> AuxModelTask.fromKey(s.taskKey)?.let { store.delete(owner, it) } ?: false }
        }
        cleanup("mcp") {
            val store = mcpServerStore ?: return@cleanup 0
            // Both stores answer a single-owner read with the shared `system` layer folded in for
            // agents, and their deletes are strictly scoped, so a row this bucket does not own would
            // survive the delete and inflate the tally. Keep only the bucket's own rows.
            val servers = store.findAll(owner).filter { it.userId == owner }
            servers.also { it.forEach { s -> store.delete(s.name, owner) } }.size
        }
        cleanup("agent") {
            val store = agentStore ?: return@cleanup 0
            val agents = store.findAll(owner).filter { it.userId == owner }
            agents.also { it.forEach { a -> store.delete(a.id, owner) } }.size
        }
        cleanup("skill") {
            val catalog = skillCatalogStore ?: return@cleanup 0
            val entries = catalog.listByUser(owner)
            val refresher = skillRefreshService
            var count = 0
            for (entry in entries) {
                // deleteSkill removes the catalog row, the install directory and the RAG slice together;
                // fall back to a bare catalog delete when the refresh service is not wired.
                val done = if (refresher != null) {
                    refresher.deleteSkill(owner, entry.name) != null
                } else {
                    catalog.delete(entry.id)
                }
                if (done) count++
            }
            count
        }
        cleanup("storage") {
            if (storageSettingsStore?.delete(owner) == true) 1 else 0
        }
        cleanup("knowledge") {
            val store = knowledgeStore ?: return@cleanup 0
            val entries = store.list(owner)
            entries.count { store.delete(owner, it.key) }
        }

        logger.info("asset_cleanup done owner={} totalRemoved={} failures={}", owner, removed.values.sum(), failures.size)
        return AssetCleanupReport(owner, removed, failures)
    }
}
