package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillEntry
import com.easy.easyai.core.skill.SkillOwnerContext
import com.easy.easyai.core.skill.SkillScope
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory
import java.nio.file.Path

/** Retrieval only ranks the same instances that load_skill can serve; it never grants access. */
internal class SkillSearchTool(
    metadata: ToolMetadata,
    private val store: SkillStore?,
    catalog: AsyncSkillCatalogStore?,
    private val searchTopK: Int = DEFAULT_SEARCH_TOP_K,
    registry: SkillRegistry,
    config: SkillConfig = SkillConfig(),
    private val allowedSkillNames: List<String> = emptyList()
) : BaseToolDefinition(metadata) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val modelView = SkillModelView(registry, catalog, config)

    data class Parameters(val query: String, val scope: String? = null, val topK: Int? = null)

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
        val scope = (args["scope"] as? String)?.trim()?.lowercase()
        val topK = ((args["topK"] as? Number)?.toInt()?.takeIf { it > 0 } ?: searchTopK).coerceIn(1, MAX_TOP_K)
        val visible = try {
            modelView.list(agentContext.userId, agentContext.projectPath, allowedSkillNames)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Skill search access is unavailable: {}", e.message)
            return result("Error: Skill catalog is unavailable; skill access could not be verified. Retry later.", true)
        }.filter {
            when (scope) {
                "global" -> modelView.scopeOf(it) == SkillScope.GLOBAL
                "project" -> modelView.scopeOf(it) == SkillScope.PROJECT
                else -> true
            }
        }
        val matches = linkedMapOf<String, ScopedSkill>()
        val ready = visible.filter { modelView.indexReady(it) }
        val slices = ready.groupBy { candidate ->
            val sliceScope = modelView.scopeOf(candidate)
            val project = if (sliceScope == SkillScope.PROJECT) agentContext.projectPath?.let {
                Path.of(SkillPaths.canonicalize(it))
            } else null
            Slice(sliceScope, SkillOwnerContext(requireNotNull(candidate.catalogEntry).userId, project))
        }
        val index = store
        if (index != null) {
            // Each owner/project address is independent. A missing project slice cannot swallow
            // GLOBAL matches, and a system fallback is per skill identity, never per whole tenant.
            for ((slice, candidates) in slices) {
                val hits = try {
                    index.search(query, listOf(slice.scope), slice.owner, topK)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.warn("Skill search index unavailable for {}: {}", slice.scope, e.message)
                    emptyList()
                }
                for (hit in hits) {
                    val candidate = candidates.firstOrNull { matchesInstance(hit, it, slice.scope) } ?: continue
                    matches.putIfAbsent(candidate.skill.name, candidate)
                }
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

    private fun matchesInstance(hit: SkillEntry, candidate: ScopedSkill, scope: SkillScope): Boolean =
        hit.name == candidate.skill.name && hit.key == SkillEntry.keyFor(candidate.skill.name) &&
            hit.scope == scope &&
            SkillPaths.canonicalizeOrNull(hit.location) == SkillPaths.canonicalize(candidate.skill.location) &&
            (hit.checksum == null || hit.checksum == candidate.catalogEntry?.checksum)

    private fun formatHit(candidate: ScopedSkill): String {
        val skill = candidate.skill
        val description = skill.description.orEmpty().lineSequence().firstOrNull().orEmpty().take(MAX_DESCRIPTION_CHARS)
        return "- [${modelView.scopeOf(candidate).name.lowercase()}] ${skill.name}: $description  (file: ${skill.location})"
    }

    private fun result(text: String, isError: Boolean = false) = ToolResult(listOf(TextContent(text)), isError = isError)

    private data class Slice(val scope: SkillScope, val owner: SkillOwnerContext)

    companion object {
        const val DEFAULT_SEARCH_TOP_K = 5
        private const val MAX_TOP_K = 20
        private const val MAX_QUERY_TERMS = 16
        private const val MAX_DESCRIPTION_CHARS = 240
    }
}
