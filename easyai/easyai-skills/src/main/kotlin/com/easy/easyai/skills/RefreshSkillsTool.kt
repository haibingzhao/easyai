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

/**
 * Makes a SKILL.md that was just written with `write` usable: re-read the skill directories, claim a
 * catalog row, push the content to the `skill_search` index, refresh the prompt view.
 *
 * Deliberately takes no domain arguments. Creating a skill is a sequence of file writes — one per
 * attachment, each of which is far too large to fit inside a single tool call's *arguments* — so the
 * writing belongs to `write` and this tool only performs the one machine step that cannot be done by
 * editing a file: republishing what the process believes exists.
 *
 * It never touches the filesystem, which is why it is reporting-heavy: everything the agent needs to
 * decide its next move (was my file picked up, who owns it now, is it loadable by this agent) is in
 * the answer.
 */
internal class RefreshSkillsTool(
    metadata: ToolMetadata,
    private val registry: SkillRegistry,
    private val refresher: SkillRefreshService?,
    private val allowedSkillNames: List<String> = emptyList()
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    data class Parameters(
        @field:JsonPropertyDescription(
            "Optional one-line note about what triggered this call, e.g. 'after writing pdf-report'. " +
                "It is only echoed into the server log; the refresh itself ignores it."
        )
        val note: String? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    /** Reads the skill directories; keep it after any concurrent `write` has finished. */
    override val executionMode = ToolExecutionMode.SEQUENTIAL

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val startedAt = System.currentTimeMillis()
        val note = (args["note"] as? String)?.takeIf { it.isNotBlank() }
        val projectPath = agentContext.projectPath

        val outcome = refresher?.refreshFor(agentContext.userId, projectPath)
        // Without a refresher this is registry-only; known roots still get rescanned because
        // rescan always unions its argument with everything it has already seen.
        val delta = outcome?.delta ?: registry.rescan(setOfNotNull(projectPath))
        val summary = outcome?.summary

        logger.info(
            "refresh_skills: note={} owner={} added={} removed={} claimed={} submitted={} " +
                "reindexed={} delisted={} failed={} backendWrites={} ms={}",
            note, outcome?.owner, delta.added, delta.removed, outcome?.claimed, outcome?.submitted,
            summary?.reindexed ?: 0, summary?.delisted ?: 0,
            summary?.failed ?: 0, summary?.backendWrites ?: 0, System.currentTimeMillis() - startedAt
        )
        return ToolResult(content = listOf(TextContent(report(delta, outcome))))
    }

    /** What happened, in the order an agent needs it: the snapshot, then the bookkeeping. */
    private fun report(delta: RegistryDelta, outcome: RefreshOutcome?): String = buildString {
        appendLine(
            "Re-read the skill directories: registered=${delta.total} added=${delta.added} removed=${delta.removed}."
        )
        val summary = outcome?.summary
        appendLine(
            if (outcome == null) {
                "Catalog and index sync are off (easyai.skills.rag.enabled=false): the skills above are " +
                    "loadable with load_skill but invisible to skill_search."
            } else buildString {
                append("Catalogued ${outcome.claimed} new skill(s) for owner '${outcome.owner}'; ")
                append("handed ${outcome.submitted} to the search index, whose embedding runs in the ")
                append("background — skill_search may be a moment behind, load_skill will not be")
                append(
                    if (summary == null) {
                        "; the skill index could not be reached."
                    } else {
                        "; its other rows: unchanged=${summary.unchanged}, reindexed=${summary.reindexed}, " +
                            "delisted=${summary.delisted}, failed=${summary.failed}."
                    }
                )
            }
        )
        append(advisoriesFor(delta, outcome))
    }

    /**
     * What the agent can still get wrong at this point, stated only when it actually applies.
     *
     * A refresh reports what happened, which is useless if the one thing the agent cared about — its
     * own new file — silently did not make it. These are the three ways that shows up.
     */
    private fun advisoriesFor(delta: RegistryDelta, outcome: RefreshOutcome?): String = buildString {
        if (delta.added.isEmpty()) {
            appendLine(
                "Nothing new was registered. If you just wrote a SKILL.md, it was not parsed: check the " +
                    "frontmatter declares both 'name' and 'description', and look for 'Failed to parse " +
                    "SKILL.md at' in the server log."
            )
        }
        val notEnabled = delta.added.filter { it !in allowedSkillNames }
        if (notEnabled.isNotEmpty()) {
            appendLine(
                "Not enabled for this agent yet: $notEnabled. Ask the user to tick them in the agent's " +
                    "skill settings — until then they stay out of the system prompt and load_skill refuses them."
            )
        }
        val delisted = outcome?.summary?.delisted ?: 0
        if (delisted > 0) {
            appendLine(
                "$delisted skill(s) lost their files on disk and were taken out of the index. Tell the user; " +
                    "re-install or re-write them to bring them back."
            )
        }
    }.ifBlank { "The new skills are usable now: verify one with load_skill(name=\"...\").\n" }
}
