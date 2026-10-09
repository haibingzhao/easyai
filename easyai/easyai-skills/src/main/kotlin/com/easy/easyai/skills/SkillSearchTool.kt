package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

/** Retrieval only ranks the same instances that load_skill can serve; it never grants access. */
internal class SkillSearchTool(
    metadata: ToolMetadata,
    private val store: SkillStore?,
    catalog: AsyncSkillCatalogStore?,
    private val searchTopK: Int = DEFAULT_SEARCH_TOP_K,
    registry: SkillRegistry,
    private val allowedSkillNames: List<String> = emptyList(),
    private val refresher: SkillRefreshService? = null
) : BaseToolDefinition(metadata) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val modelView = SkillModelView(registry, catalog)

    data class Parameters(val query: String, val topK: Int? = null)

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val query = (args["query"] as? String)?.trim()
        if (query.isNullOrBlank()) return result("Error: 'query' parameter is required. Describe the task you need help with.", true)
        if (allowedSkillNames.isEmpty()) return result("Error: No skills are authorized for this agent.", true)
        val topK = ((args["topK"] as? Number)?.toInt()?.takeIf { it > 0 } ?: searchTopK).coerceIn(1, MAX_TOP_K)
        refresher?.ensureSyncedOwners(agentContext.effectiveOwners, agentContext.userId)
        val visible = try {
            modelView.listForOwners(agentContext.effectiveOwners, allowedSkillNames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill search access is unavailable: {}", e.message)
            return result("Error: Skill catalog is unavailable; skill access could not be verified. Retry later.", true)
        }
        val matches = linkedMapOf<String, ScopedSkill>()
        val ready = visible.filter { modelView.indexReady(it) }
        val index = store
        if (index != null && ready.isNotEmpty()) {
            // One search across the requester's slice and the shared `system` slice; the backend
            // dedupes by name with the *first* slice winning, so the requester must be listed first
            // regardless of which skill names sort earlier. The candidate gate below re-checks
            // every hit against the authoritative local view.
            val owners = ready.map { it.catalogEntry!!.userId }.distinct()
            val requester = agentContext.userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
            val orderedOwners = owners.filter { it == requester } + owners.filter { it != requester }
            val hits = try {
                index.search(query, orderedOwners, topK * HIT_OVERFETCH)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Skill search index unavailable: {}", e.message)
                emptyList()
            }
            for (hit in hits) {
                val candidate = ready.firstOrNull { matchesInstance(hit, it) } ?: continue
                matches.putIfAbsent(candidate.skill.name, candidate)
            }
        }
        // Bounded name/description fallback also handles stores that report outages as no hits.
        // Never search raw registry entries or use descriptions supplied by unverified index hits.
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }.take(MAX_QUERY_TERMS)
        visible.asSequence().filter { candidate ->
            val text = "${candidate.skill.name} ${candidate.skill.description.orEmpty()}".lowercase()
            terms.any { it in text }
        }.take(topK).forEach { matches.putIfAbsent(it.skill.name, it) }
        val selected = matches.values.take(topK)
        if (selected.isEmpty()) return result("No skills found matching '$query'. Try broader task wording.")
        return result(
            "Found ${selected.size} skill(s) for '$query':\n\n" +
                selected.joinToString("\n") { formatHit(it) } +
                "\nLoad the full instructions with `load_skill` by name."
        )
    }

    private fun matchesInstance(hit: SkillEntry, candidate: ScopedSkill): Boolean =
        hit.name == candidate.skill.name && hit.key == SkillEntry.keyFor(candidate.skill.name) &&
            SkillPaths.canonicalizeOrNull(hit.location) == SkillPaths.canonicalize(candidate.skill.location) &&
            (hit.checksum == null || hit.checksum == candidate.catalogEntry?.checksum)

    private fun formatHit(candidate: ScopedSkill): String {
        val skill = candidate.skill
        val description = skill.description.orEmpty().lineSequence().firstOrNull().orEmpty().take(MAX_DESCRIPTION_CHARS)
        val marker = if (candidate.shared) "shared" else "mine"
        return "- [$marker] ${skill.name}: $description  (file: ${skill.location})"
    }

    private fun result(text: String, isError: Boolean = false) = ToolResult(listOf(TextContent(text)), isError = isError)

    companion object {
        const val DEFAULT_SEARCH_TOP_K = 5
        private const val MAX_TOP_K = 20

        /** Extra hits per owner slice so shadowed duplicates still leave enough visible candidates. */
        private const val HIT_OVERFETCH = 2
        private const val MAX_QUERY_TERMS = 16
        private const val MAX_DESCRIPTION_CHARS = 240
    }
}
