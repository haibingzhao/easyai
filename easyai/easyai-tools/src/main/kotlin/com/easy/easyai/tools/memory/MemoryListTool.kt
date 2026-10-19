package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryMaturity
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate
import java.time.temporal.ChronoUnit

internal class MemoryListTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(val type: String? = null)
    override fun parameterType(): Class<*> = Parameters::class.java

    /** Days since the entry was last relevant: last retrieval, else last change, else creation. */
    private fun unusedDays(entry: MemoryEntry): Long? {
        val reference = entry.lastAccessed ?: entry.updated ?: entry.created ?: return null
        return ChronoUnit.DAYS.between(reference, LocalDate.now())
    }

    /**
     * Review candidates are low-maturity entries nobody has touched in a long time: they may
     * be obsolete but the model has to verify them before updating or removing anything.
     */
    private fun isReviewCandidate(entry: MemoryEntry, unusedDays: Long?): Boolean =
        entry.maturity == MemoryMaturity.LOW && unusedDays != null && unusedDays >= STALE_AFTER_DAYS

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val typeFilter = (args["type"] as? String)?.let { MemoryType.fromDirName(it) }
        val owner = MemoryOwnerContext(agentContext.userId, agentContext.projectPath)

        val sb = StringBuilder()
        val scopeDetails = mutableListOf<Map<String, Any?>>()

        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val entries = store.list(scope, owner, typeFilter)
            if (entries.isEmpty()) continue

            val ordered = entries.sortedBy { it.type.dirName }
            sb.appendLine("## ${scope.name} scope (${entries.size} entries)")
            sb.appendLine()
            val reviewCandidates = mutableListOf<String>()
            val reviewDetails = mutableListOf<Map<String, Any?>>()
            ordered.forEach { entry ->
                sb.appendLine("- **${entry.name}** [${entry.type.dirName}] — ${entry.description}")
                val unusedDays = unusedDays(entry)
                val unusedText = unusedDays?.let { "$it days" } ?: "unknown"
                // An entry without a docId cannot be cited by any tool that takes one, so the field is
                // dropped rather than rendered empty — an empty id invites the model to pass "".
                val docIdField = entry.id?.let { "docId: `$it` | " } ?: ""
                sb.appendLine(
                    "  ${docIdField}path: `${entry.path}` | level: ${entry.level} | " +
                        "created: ${entry.created ?: "unknown"} | " +
                        "updated: ${entry.updated ?: "unknown"} | " +
                        "maturity: ${entry.maturity?.apiName ?: "unset"} | unused for $unusedText"
                )
                if (isReviewCandidate(entry, unusedDays)) {
                    reviewCandidates.add(
                        "- ${entry.name} [${entry.type.dirName}] unused for $unusedText → verify it; " +
                            "use memory_write action='update' to correct or action='remove' if obsolete"
                    )
                    reviewDetails.add(
                        mapOf(
                            "name" to entry.name,
                            "type" to entry.type.dirName,
                            "unusedDays" to unusedDays
                        )
                    )
                }
            }
            if (reviewCandidates.isNotEmpty()) {
                sb.appendLine()
                sb.appendLine("### Review candidates")
                sb.appendLine("Low-maturity entries untouched for ${STALE_AFTER_DAYS}+ days. Do not delete blindly — read each one first.")
                reviewCandidates.forEach { sb.appendLine(it) }
            }
            sb.appendLine()

            scopeDetails.add(
                mapOf(
                    "scope" to scope.name.lowercase(),
                    "count" to entries.size,
                    "entries" to ordered.map { entryDetails(it) },
                    "reviewCandidates" to reviewDetails,
                    "staleAfterDays" to STALE_AFTER_DAYS
                )
            )
        }

        val text = if (sb.isEmpty()) {
            "No memory entries found${if (typeFilter != null) " for type '${typeFilter.dirName}'" else ""}."
        } else {
            sb.toString()
        }
        return ToolResult(
            content = listOf(TextContent(text)),
            details = if (scopeDetails.isEmpty()) emptyMap() else mapOf("scopes" to scopeDetails)
        )
    }

    /**
     * One entry as the renderer needs it. The prose in the tool output is addressed to the model and
     * carries no contract; this map is the contract, with dates as ISO strings and staleness as a day
     * count so the UI can localize instead of re-parsing English text.
     */
    private fun entryDetails(entry: MemoryEntry): Map<String, Any?> = mapOf(
        "name" to entry.name,
        "type" to entry.type.dirName,
        "description" to entry.description,
        "path" to entry.path,
        "updated" to entry.updated?.toString(),
        "maturity" to entry.maturity?.apiName,
        // The same two the prose line carries: level is what tells a merged entry apart from a source,
        // and without the docId a listed entry cannot be cited by any tool that takes one.
        "level" to entry.level,
        "docId" to entry.id,
        "unusedDays" to unusedDays(entry)
    )

    private companion object {
        /** Unused-days threshold past which a low-maturity entry is listed for review. */
        const val STALE_AFTER_DAYS = 90L
    }
}
