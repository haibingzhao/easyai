package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

/**
 * Publishes skills the agent just wrote: re-reconciles the requester's owner root (claim new
 * directories, push drifted content to package + catalog) and re-indexes both the personal and
 * the shared layer. The shared `system` root is read-only for regular users, so a skill written
 * by an agent is always claimed into the writer's own root.
 */
internal class RefreshSkillsTool(
    metadata: ToolMetadata,
    private val registry: SkillRegistry,
    private val refresher: SkillRefreshService?,
    private val config: SkillConfig,
    private val allowedSkillNames: List<String> = emptyList()
) : BaseToolDefinition(metadata) {
    private val logger = LoggerFactory.getLogger(javaClass)

    data class Parameters(
        @field:JsonPropertyDescription("Optional one-line note about what triggered this refresh.")
        val note: String? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java
    override val executionMode = ToolExecutionMode.SEQUENTIAL

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val owner = refresher?.ownersForOwners(agentContext.effectiveOwners) ?: emptyList()
        val outcome = refresher?.refreshForOwners(agentContext.effectiveOwners, agentContext.userId)
        val delta = outcome?.delta ?: run {
            val viewer = agentContext.userId?.takeIf { it.isNotBlank() } ?: SkillCatalogEntry.DEFAULT_USER_ID
            registry.rescan(setOf(SkillPaths.ownerRoot(config, viewer)))
        }
        logger.info(
            "refresh_skills: note={} owners={} claimed={} pushed={} restored={} backfilled={} " +
                "unclaimed={} submitted={} failed={}",
            args["note"], owner, outcome?.sync?.claimed, outcome?.sync?.pushed, outcome?.sync?.restored,
            outcome?.sync?.backfilled, outcome?.sync?.unclaimed,
            outcome?.summary?.submitted, outcome?.summary?.failed
        )
        return ToolResult(content = listOf(TextContent(report(delta, outcome))))
    }

    private fun report(delta: RegistryDelta, outcome: RefreshOutcome?): String = buildString {
        appendLine("Re-read the skill directories: registered=${delta.total} added=${delta.added.map { it.name }} " +
            "updated=${delta.updatedKeys.map { it.name }} removed=${delta.removed.map { it.name }}.")
        if (outcome == null) {
            appendLine("Catalog coordination is unavailable. Registration alone does not establish ownership or search readiness.")
        } else {
            val sync = outcome.sync
            if (sync == null) {
                appendLine("Catalog/package reconciliation could not run; local files were only registered.")
            } else {
                appendLine("For owners ${outcome.owners}: claimed=${sync.claimed}, pushed=${sync.pushed} " +
                    "(local content re-uploaded to the package store), restored=${sync.restored}, " +
                    "backfilled=${sync.backfilled} (packages that were absent from the current " +
                    "storage re-uploaded from unchanged local content), " +
                    "skipped=${sync.skipped}, failed=${sync.failed}.")
                if (sync.unclaimed > 0) {
                    appendLine("Shared-layer directories left unclaimed=${sync.unclaimed}: the system root only " +
                        "installs skills that already have a catalog row, so hand-placed files there stay private.")
                }
            }
            val summary = outcome.summary
            if (summary == null) {
                appendLine("Index synchronization could not be inspected; readiness is unknown.")
            } else {
                appendLine("Content updated=${summary.updated}; submitted=${summary.submitted}, confirmed=${summary.confirmed}, " +
                    "deleted=${summary.delisted}, pending=${summary.pending}, failed=${summary.failed}, unchanged=${summary.unchanged}.")
                appendLine("Submitted is not searchable: only the target checksum confirmed processed is ready. " +
                    "Pending work can be retried by refresh or the background reconciler.")
            }
        }
        if (delta.added.isEmpty() && delta.updatedKeys.isEmpty()) {
            appendLine("No new or changed source was registered. Check parse warnings if a file looks stale.")
        }
        val notAllowed = delta.added.map { it.name }.filter { it !in allowedSkillNames }
        if (notAllowed.isNotEmpty()) {
            appendLine("Not enabled for this agent yet: $notAllowed. Ask the user to select them in the agent's skill settings.")
        }
        append("load_skill additionally requires the current user, catalog enablement and agent allowlist to permit the source.")
    }
}
