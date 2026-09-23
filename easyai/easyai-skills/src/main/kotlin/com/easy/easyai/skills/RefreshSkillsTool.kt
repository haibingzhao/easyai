package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import org.slf4j.LoggerFactory

internal class RefreshSkillsTool(
    metadata: ToolMetadata,
    private val registry: SkillRegistry,
    private val refresher: SkillRefreshService?,
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
        val outcome = refresher?.refreshFor(agentContext.userId, agentContext.projectPath)
        val delta = outcome?.delta ?: registry.rescan(setOfNotNull(agentContext.projectPath))
        logger.info("refresh_skills: note={} owner={} claimed={} submitted={} failed={}",
            args["note"], outcome?.owner, outcome?.claimed, outcome?.submitted, outcome?.summary?.failed)
        return ToolResult(content = listOf(TextContent(report(delta, outcome))))
    }

    private fun report(delta: RegistryDelta, outcome: RefreshOutcome?): String = buildString {
        appendLine("Re-read the skill directories: registered=${delta.total} added=${delta.added} " +
            "updated=${delta.updatedKeys.map { it.name }} removed=${delta.removed}.")
        if (outcome == null) {
            appendLine("Catalog coordination is unavailable. Registration alone does not establish ownership or search readiness.")
        } else {
            appendLine("Catalogued ${outcome.claimed} new skill(s) for owner '${outcome.owner}'; " +
                "claimFailed=${outcome.claimFailed}, unclaimed=${outcome.unclaimed}.")
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
        val notAllowed = delta.added.filter { it !in allowedSkillNames }
        if (notAllowed.isNotEmpty()) {
            appendLine("Not enabled for this agent yet: $notAllowed. Ask the user to select them in the agent's skill settings.")
        }
        append("load_skill additionally requires the current user/project, catalog enablement and agent allowlist to permit the source.")
    }
}
