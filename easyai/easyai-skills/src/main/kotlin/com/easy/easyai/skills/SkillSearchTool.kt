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
import java.util.concurrent.atomic.AtomicReference

/**
 * Semantic discovery over the skill slices: `skill_search`.
 *
 * This is the read half of "stop putting every skill in every prompt", and it carries the first two
 * of the three authorization gates:
 * 1. **Tenant isolation happens in storage** — the biz id set is derived from the resolved owner, so
 *    another user's skills are physically unreachable; no metadata filtering is involved
 * 2. **Enablement is a catalog filter** on the hits, so a disabled or delisted skill cannot surface
 *    out of a stale index
 *
 * The third gate stays in `load_skill`, which checks the agent whitelist and the catalog row before
 * reading anything from disk.
 *
 * Output is advisory text, never an error result: an empty or failed search must not stall the loop.
 */
internal class SkillSearchTool(
    metadata: ToolMetadata,
    private val store: SkillStore,
    private val catalog: AsyncSkillCatalogStore?,
    private val searchTopK: Int = DEFAULT_SEARCH_TOP_K
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Owner and enablement set of this user, memoised per tool instance.
     *
     * A tool instance is built per agent context, so this is request-scoped by construction: many
     * searches in one exchange read the table once. A null `enabledNames` means "catalog unreadable",
     * in which case hits pass through rather than disappearing behind an infrastructure problem.
     *
     * [AtomicReference] rather than a plain `var`: the agent loop can dispatch multiple tool calls
     * concurrently on the same instance, and a non-volatile field would let both racers observe
     * `null` and duplicate the DB round trip (plus JMM would not guarantee publication).
     */
    private val tenantRef = AtomicReference<SkillTenant?>(null)

    data class Parameters(
        val query: String,
        val scope: String? = null,
        val topK: Int? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val query = args["query"] as? String
        if (query.isNullOrBlank()) {
            return ToolResult(
                content = listOf(TextContent("Error: 'query' parameter is required. Describe the task you need help with.")),
                isError = true
            )
        }
        val scopeFilter = (args["scope"] as? String)?.trim()?.lowercase()
        val topK = (args["topK"] as? Number)?.toInt()?.takeIf { it > 0 } ?: searchTopK

        val tenant = resolveTenant(agentContext.userId)
        val owner = SkillOwnerContext(tenant.userId, agentContext.projectPath)
        val hits = runCatchingSearch { store.search(query, scopesOf(scopeFilter, owner), owner, topK) }
        return ToolResult(
            content = listOf(TextContent(renderLocal(filterByCatalog(hits, tenant), query, tenant)))
        )
    }

    /** Discovery is optional; a backend outage must look like "nothing matched", never an error. */
    private suspend fun runCatchingSearch(search: suspend () -> List<SkillEntry>): List<SkillEntry> =
        try {
            search()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("skill_search degraded: {}", e.message)
            emptyList()
        }

    /**
     * Scopes to query, defaulting to both slices of this owner.
     *
     * GLOBAL and PROJECT go out in the **same** request (`SkillStore.search` sends them as one biz
     * id set), which is what keeps discovery affordable while replacing a full name+description dump.
     */
    private fun scopesOf(scopeFilter: String?, owner: SkillOwnerContext): List<SkillScope> = when (scopeFilter) {
        "global" -> listOf(SkillScope.GLOBAL)
        "project" -> listOf(SkillScope.PROJECT)
        else -> buildList {
            add(SkillScope.GLOBAL)
            if (owner.projectPath != null) add(SkillScope.PROJECT)
        }
    }

    /** Read the tenant facts once per exchange; both gates are decided from the same snapshot. */
    private suspend fun resolveTenant(requestedUserId: String?): SkillTenant {
        tenantRef.get()?.let { return it }
        val resolved = SkillOwnership.tenantOf(catalog, requestedUserId)
        // compareAndSet rather than set: two concurrent racers both fetching the same tenant is
        // harmless (idempotent), and CAS keeps a single winner without a lock.
        tenantRef.compareAndSet(null, resolved)
        return tenantRef.get() ?: resolved
    }

    /** Second gate: drop hits the catalog does not vouch for. */
    private fun filterByCatalog(hits: List<SkillEntry>, tenant: SkillTenant): List<SkillEntry> {
        if (hits.isEmpty()) return hits
        val names = tenant.enabledNames ?: return hits
        return hits.filter { it.name in names }
    }

    private fun renderLocal(visible: List<SkillEntry>, query: String, tenant: SkillTenant): String {
        if (visible.isEmpty()) {
            val recorded = tenant.enabledNames?.size ?: 0
            val staleHint = if (recorded > 0) {
                " $recorded enabled skill(s) are on record for '${tenant.userId}', so the index may still be warming up."
            } else {
                ""
            }
            return "No skills found matching '$query'." +
                staleHint +
                " Try broader task wording."
        }
        val header = "Found ${visible.size} skill(s) for '$query' (user '${tenant.userId}'):"
        val footer = "Load the full instructions of one of them with `load_skill` by name. " +
            "Only the leading text is shown here; the SKILL.md on disk is authoritative."
        return "$header\n\n${visible.joinToString("\n") { formatHit(it) }}\n$footer"
    }

    private fun formatHit(entry: SkillEntry): String {
        val granularity = entry.scope?.name?.lowercase() ?: "global"
        val tags = entry.tags.take(MAX_TAGS).joinToString(", ").takeIf { it.isNotEmpty() }
        val description = entry.description.lineSequence().first().take(MAX_DESCRIPTION_CHARS)
        val location = entry.location?.let { "  (file: $it)" } ?: ""
        return "- [$granularity] ${entry.name}: $description${tags?.let { " [$it]" } ?: ""}$location"
    }

    companion object {
        const val DEFAULT_SEARCH_TOP_K = 5

        private const val MAX_TAGS = 6
        private const val MAX_DESCRIPTION_CHARS = 240
    }
}
