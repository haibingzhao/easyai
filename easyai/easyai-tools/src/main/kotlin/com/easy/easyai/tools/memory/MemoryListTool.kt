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

        for (scope in listOf(MemoryScope.PROJECT, MemoryScope.GLOBAL)) {
            val entries = store.list(scope, owner, typeFilter)
            if (entries.isEmpty()) continue

            sb.appendLine("## ${scope.name} scope (${entries.size} entries)")
            sb.appendLine()
            val reviewCandidates = mutableListOf<String>()
            entries.sortedBy { it.type.dirName }.forEach { entry ->
                sb.appendLine("- **${entry.name}** [${entry.type.dirName}] — ${entry.description}")
                val unusedDays = unusedDays(entry)
                val unusedText = unusedDays?.let { "$it days" } ?: "unknown"
                sb.appendLine(
                    "  path: `${entry.path}` | created: ${entry.created ?: "unknown"} | " +
                        "updated: ${entry.updated ?: "unknown"} | " +
                        "maturity: ${entry.maturity?.apiName ?: "unset"} | unused for $unusedText"
                )
                if (isReviewCandidate(entry, unusedDays)) {
                    reviewCandidates.add(
                        "- ${entry.name} [${entry.type.dirName}] unused for $unusedText → verify it; " +
                            "use memory_write action='update' to correct or action='remove' if obsolete"
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
        }

        val text = if (sb.isEmpty()) {
            "No memory entries found${if (typeFilter != null) " for type '${typeFilter.dirName}'" else ""}."
        } else {
            sb.toString()
        }
        return ToolResult(content = listOf(TextContent(text)))
    }

    private companion object {
        /** Unused-days threshold past which a low-maturity entry is listed for review. */
        const val STALE_AFTER_DAYS = 90L
    }
}
