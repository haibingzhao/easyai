package com.easy.easyai.core.memory

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.memory.consolidation.CONSOLIDATION_OUTPUT_SCHEMA
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationOutput
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunHandle
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunLifecycle
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunState
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunStats
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationWriter
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.validation.JsonExtractor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Outcome of one consolidation run for a single `(slice, type)`.
 *
 * @property activeBefore active entries of that type in the slice before the run
 * @property mergedWritten merged documents written (0 in a dry run)
 * @property archived source documents archived into a merged document (0 in a dry run)
 * @property archiveShortfall sources a written cluster failed to archive, so they are still live
 *   duplicates of the merged document above them
 * @property skippedSingletons clusters dropped because they had fewer than 2 usable sources
 * @property skippedOverCap clusters deferred because the per-run archive budget ran out
 * @property rejectedBatches batches whose answer could not be applied at all: a first answer that was
 *   unusable, or a re-ask that came back unusable after the batch had already been part-applied (the
 *   applied part stays applied — this counts batches, not work lost)
 * @property uncoveredDocIds sources the model neither clustered nor declared as leftovers
 * @property plan per-cluster detail; populated for dry runs and for auditing a real run
 */
data class ConsolidationReport(
    val type: MemoryType,
    val scope: MemoryScope,
    val activeBefore: Int,
    val mergedWritten: Int = 0,
    val archived: Int = 0,
    val archiveShortfall: Int = 0,
    val skippedSingletons: Int = 0,
    val skippedOverCap: Int = 0,
    val rejectedBatches: Int = 0,
    val uncoveredDocIds: Int = 0,
    val dryRun: Boolean = false,
    val sessionId: String? = null,
    val plan: List<Map<String, Any?>> = emptyList()
)

/**
 * A consolidation run outlived its wall-clock budget.
 *
 * [IllegalStateException] on purpose: the async-task runner reads a plain [kotlinx.coroutines.CancellationException]
 * as a user cancel and would park the row CANCELLED with no reason at all, and the web layer needs a
 * type of its own to answer 504 rather than the 400 every other configuration failure earns.
 */
class MemoryConsolidationTimeoutException(
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

/**
 * Runs a consolidation pass through a real, persisted Agent Loop.
 *
 * Everything the model does lands in a hidden session (its row carries
 * `session.system_kind = async_task`, which is what filters it out of Chat History): one user message
 * per budget-sized batch dumps that batch's active entries with their docIds, and the model answers
 * with a single [MemoryConsolidationOutput] JSON object.
 * The loop enforces that shape — at the API level where the protocol can express the schema, through
 * [com.easy.easyai.core.validation.OutputSchemaCompletionCheck] where it cannot — and this class owns
 * every write, so a model that answers with prose instead of JSON loses the batch instead of silently
 * skipping the work.
 *
 * The model never deletes memories: absorbed sources are archived (`archived=true`,
 * `merged_into=<merged docId>`) and the merged document references them via `ref_ids`, forming the
 * multi-level memory graph.
 */
class MemoryConsolidationAgent(
    private val store: MemoryStore,
    private val sessionManager: SessionManager? = null,
    private val agentService: AgentService? = null,
    private val settings: MemoryConsolidationSettings = MemoryConsolidationSettings(),
    private val auxResolver: AuxModelResolver? = null,
    private val lifecycle: MemoryConsolidationRunLifecycle? = null
) {

    private val writer = MemoryConsolidationWriter(store)

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Consolidate [type] inside [scope]/[owner].
     *
     * A preview ([dryRun]) is awaited by an HTTP request, so it runs on the shorter
     * [MemoryConsolidationSettings.dryRunTimeoutMs] budget instead of the full task budget.
     *
     * @param sessionId hidden session to reuse (task retry); a fresh id is minted when null
     * @throws IllegalStateException when no MEMORY_CURATION model is configured, no chat-model
     *   factory supports its protocol, or the run outlived its budget; the task surfaces this as its
     *   failure reason. A budget overrun is a [MemoryConsolidationTimeoutException], so a caller that
     *   waits on it can answer with a timeout rather than a bad-request.
     */
    suspend fun consolidate(
        scope: MemoryScope,
        owner: MemoryOwnerContext,
        type: MemoryType,
        owners: Collection<String> = emptyList(),
        sessionId: String? = null,
        dryRun: Boolean = false
    ): ConsolidationReport {
        val budgetMs = if (dryRun) settings.dryRunTimeoutMs else settings.runTimeoutMs
        return try {
            withTimeout(budgetMs.milliseconds) {
                runPass(scope, owner, type, owners, sessionId, dryRun)
            }
        } catch (e: TimeoutCancellationException) {
            // TimeoutCancellationException is a CancellationException, and the async-task runner reads
            // that as a user cancel: the row would be parked CANCELLED with no error text at all.
            throw MemoryConsolidationTimeoutException(
                "Memory consolidation timed out after ${budgetMs / 1000}s (scope=$scope, type=${type.dirName})",
                e
            )
        }
    }

    private suspend fun runPass(
        scope: MemoryScope,
        owner: MemoryOwnerContext,
        type: MemoryType,
        owners: Collection<String>,
        sessionId: String?,
        dryRun: Boolean
    ): ConsolidationReport {
        val userId = owner.userId
            ?: throw IllegalStateException("Memory consolidation requires an owning user")
        if (!settings.available) {
            throw IllegalStateException("Memory consolidation is disabled by configuration")
        }
        // Bound here so a misconfigured context fails with an actionable message instead of an NPE.
        val sessionManager = sessionManager
            ?: throw IllegalStateException("Memory consolidation requires a SessionManager")
        val agentService = agentService
            ?: throw IllegalStateException("Memory consolidation requires an AgentService")
        val ownerSet = owners.ifEmpty { listOf(userId) }
        val modelConfig = resolveCurationConfig(ownerSet)
        val factory = agentService.chatModelFactories.firstOrNull { it.supports(modelConfig.protocol) }
            ?: throw IllegalStateException(
                "No ChatModelFactory supports protocol '${modelConfig.protocol}' for memory curation"
            )

        val active = store.list(scope, owner, type)
            .distinctBy { it.id ?: it.path }
        if (active.size < 2) {
            logger.debug("Memory consolidation skipped for {} {}: {} active entries", scope, type.dirName, active.size)
            return ConsolidationReport(
                type = type,
                scope = scope,
                activeBefore = active.size,
                dryRun = dryRun,
                sessionId = sessionId
            )
        }

        val sid = sessionId ?: UUID.randomUUID().toString()
        val baseContext = AgentContext(
            agentId = AGENT_ID,
            modelConfig = modelConfig,
            sessionId = sid,
            userId = userId,
            // Stamped on the session row itself: this transcript is not a chat, and that must survive
            // the async_task row pointing at it being deleted from the Tasks page.
            systemKind = AgentContext.SYSTEM_KIND_ASYNC_TASK,
            owners = ownerSet.toSet(),
            projectPath = owner.projectPath.takeIf { scope == MemoryScope.PROJECT },
            maxIterations = settings.maxIterations,
            customInstructions = SYSTEM_PROMPT,
            outputSchema = CONSOLIDATION_OUTPUT_SCHEMA
        )
        val runState = MemoryConsolidationRunState(
            type = type,
            scope = scope,
            owner = owner,
            dryRun = dryRun,
            maxArchives = settings.maxArchivesPerRun.coerceAtLeast(1),
            stats = MemoryConsolidationRunStats()
        )
        // No tools: the run's only output channel is the schema-conforming answer, and this class is
        // the only writer. A chat turn over the same session therefore cannot reach the store either.
        val session = sessionManager.getOrCreateSession(baseContext, modelConfig, factory)

        var handle: MemoryConsolidationRunHandle? = null
        try {
            handle = lifecycle?.begin(sid, userId)
            val batches = batchByBudget(active)
            logger.info(
                "Memory consolidation start: scope={}, type={}, entries={}, batches={}, dryRun={}, session={}",
                scope, type.dirName, active.size, batches.size, dryRun, sid
            )
            batches.forEachIndexed { index, batch ->
                val docIds = batch.mapNotNull { it.id }
                val prompt = buildPrompt(type, scope, batch, index + 1, batches.size, runState)
                applyBatch(session, prompt, docIds, runState)
            }
        } finally {
            handle?.end(session.lastEndReason)
        }

        val stats = runState.stats
        val report = ConsolidationReport(
            type = type,
            scope = scope,
            activeBefore = active.size,
            mergedWritten = stats.mergedWritten.get(),
            archived = stats.archived.get(),
            archiveShortfall = stats.archiveShortfall.get(),
            skippedSingletons = stats.skippedSingletons.get(),
            skippedOverCap = stats.skippedOverCap.get(),
            rejectedBatches = stats.rejectedBatches.get(),
            uncoveredDocIds = stats.uncoveredSources.get(),
            dryRun = dryRun,
            sessionId = sid,
            plan = stats.plan()
        )
        logger.info(
            "Memory consolidation done: scope={}, type={}, merged={}, archived={}, archiveShortfall={}, " +
                "skippedSingletons={}, skippedOverCap={}, rejectedBatches={}, uncoveredSources={}",
            scope, type.dirName, report.mergedWritten, report.archived, report.archiveShortfall,
            report.skippedSingletons, report.skippedOverCap, report.rejectedBatches, report.uncoveredDocIds
        )
        return report
    }

    /**
     * Ask for one batch and apply whatever it produced.
     *
     * The loop has already spent its own nudges trying to get schema-conforming text by the time
     * [session.prompt] returns, so an unusable answer here is final: the batch is counted as rejected
     * and dropped rather than retried a second time over the same budget.
     *
     * A failed re-ask earns the same counter even though the first answer already committed clusters:
     * [ConsolidationReport.rejectedBatches] counts batches that did not finish, and the clusters that
     * landed are reported by [ConsolidationReport.mergedWritten] and the plan rows.
     */
    private suspend fun applyBatch(
        session: ChatSession,
        prompt: String,
        docIds: List<String>,
        runState: MemoryConsolidationRunState
    ) {
        val stats = runState.stats
        val output = ask(session, prompt)
        if (output == null) {
            stats.rejectedBatches.incrementAndGet()
            stats.uncoveredSources.addAndGet(docIds.size)
            return
        }
        val first = writer.writeBatch(docIds, output, runState)
        if (first.uncoveredDocIds.isEmpty()) return
        // One bounded re-ask: the entries were already shown in this session, so naming the docIds that
        // went unaccounted for is enough for the model to finish the batch.
        val retry = ask(session, uncoveredPrompt(first.uncoveredDocIds))
        if (retry == null) {
            stats.rejectedBatches.incrementAndGet()
            stats.uncoveredSources.addAndGet(first.uncoveredDocIds.size)
            return
        }
        val second = writer.writeBatch(first.uncoveredDocIds, retry, runState)
        // Counted only once, from the gap that survived the re-ask — a recovered batch must not
        // still report what the first answer missed.
        stats.uncoveredSources.addAndGet(second.uncoveredDocIds.size)
    }

    /** Run one prompt in the session and deserialize the schema-conforming answer it should produce. */
    private suspend fun ask(session: ChatSession, prompt: String): MemoryConsolidationOutput? {
        val text = session.prompt(prompt, emptyList()).result().lastOrNull()?.text().orEmpty()
        val json = JsonExtractor.extract(text)
        if (json == null) {
            logger.warn("Consolidation batch produced no JSON: {}", text.take(MAX_LOGGED_CHARS))
            return null
        }
        return try {
            SharedObjectMapper.instance.readValue(json, MemoryConsolidationOutput::class.java)
        } catch (e: Exception) {
            // The loop gives up on its last invalid answer, so a failure here is expected occasionally
            // and must cost one batch, not the run.
            logger.warn("Consolidation batch JSON does not match the schema: {}", e.message)
            null
        }
    }

    private fun uncoveredPrompt(uncoveredDocIds: List<String>): String = buildString {
        appendLine("These docIds from the previous batch appeared in no cluster and were not listed as leftovers:")
        appendLine(uncoveredDocIds.joinToString())
        appendLine("Answer again with the full JSON object for just these entries: cluster the ones that")
        appendLine("belong together, and list every entry you judge unmergeable in 'leftoverDocIds'.")
    }

    private suspend fun resolveCurationConfig(owners: Collection<String>) =
        auxResolver?.resolveConfig(owners, AuxModelTask.MEMORY_CURATION)
            ?: throw IllegalStateException(
                "Memory curation model is not configured; set it in Settings → Task Models"
            )

    /**
     * Cut one slice's entries into prompt batches: a batch ends when the next entry would cross
     * [MemoryConsolidationSettings.maxPromptCharsPerBatch] or the batch already holds
     * [MemoryConsolidationSettings.maxEntriesPerBatch] entries. Entry count alone is not a bound — an
     * entry may be [MemoryContentLimits.CONTENT_HARD_CAP_CHARS] characters long — and an entry over
     * the budget still travels alone rather than being dropped.
     */
    internal fun batchByBudget(entries: List<MemoryEntry>): List<List<MemoryEntry>> {
        val budget = settings.maxPromptCharsPerBatch.coerceAtLeast(MIN_PROMPT_CHARS)
        val maxEntries = settings.maxEntriesPerBatch.coerceAtLeast(1)
        val batches = mutableListOf<List<MemoryEntry>>()
        var batch = mutableListOf<MemoryEntry>()
        var chars = PROMPT_HEADER_CHARS
        for (entry in entries) {
            val cost = entryPromptChars(entry)
            if (batch.isNotEmpty() && (chars + cost > budget || batch.size >= maxEntries)) {
                batches += batch.toList()
                batch = mutableListOf()
                chars = PROMPT_HEADER_CHARS
            }
            batch += entry
            chars += cost
        }
        if (batch.isNotEmpty()) batches += batch.toList()
        return batches
    }

    private fun entryPromptChars(entry: MemoryEntry): Int =
        entry.content.length + entry.name.length + entry.description.length + ENTRY_FRAME_CHARS

    /** One user message per batch: every active entry of that batch with the docId the output must cite. */
    private fun buildPrompt(
        type: MemoryType,
        scope: MemoryScope,
        entries: List<MemoryEntry>,
        batchIndex: Int,
        batchCount: Int,
        runState: MemoryConsolidationRunState
    ): String = buildString {
        appendLine("Memory consolidation run.")
        appendLine("Type: ${type.dirName}")
        appendLine("Slice: ${scope.name}")
        appendLine("Batch: $batchIndex/$batchCount")
        appendLine("Active entries in this batch: ${entries.size}")
        appendLine("Archive budget remaining: ${runState.remainingArchives()}")
        appendLine()
        appendLine("Classify these entries. Only entries that belong together may form a cluster;")
        appendLine("a cluster needs at least 2 source entries. An entry with no sibling goes into")
        appendLine("'leftoverDocIds' instead of a cluster.")
        appendLine("Answer with a single JSON object matching the required output schema — every docId")
        appendLine("from this batch must appear in exactly one cluster or in 'leftoverDocIds'.")
        appendLine()
        appendLine("<memories>")
        entries.forEach { entry ->
            appendLine(
                "<memory docId=\"${escapeAttribute(entry.id).orEmpty()}\" name=\"${escapeAttribute(entry.name)}\" " +
                    "type=\"${entry.type.dirName}\" " +
                    "level=\"${entry.level}\" updated=\"${escapeAttribute(entry.updated?.toString()) ?: "unknown"}\" " +
                    "maturity=\"${entry.maturity?.apiName ?: "unset"}\">"
            )
            appendLine("description: ${entry.description}")
            appendLine(neutralizeCloseTag(entry.content))
            appendLine("</memory>")
        }
        appendLine("</memories>")
    }

    /** Attribute values are interpolated into a quoted XML-ish attribute, so quotes must not end it. */
    private fun escapeAttribute(value: String?): String? = value?.replace("&", "&amp;")
        ?.replace("\"", "&quot;")
        ?.replace("<", "&lt;")
        ?.replace(">", "&gt;")

    /** A stored body that happens to carry the framing tag would otherwise end the element early. */
    private fun neutralizeCloseTag(content: String): String =
        content.replace("</memory", "<//memory")

    private companion object {
        const val AGENT_ID = "memory-consolidation"

        /** Fixed instruction block of one batch prompt, measured generously. */
        const val PROMPT_HEADER_CHARS = 800

        /** Per-entry framing (`<memory …>` tag, description line, closing tag). */
        const val ENTRY_FRAME_CHARS = 120

        /** A budget below one hard-capped entry would emit empty batches. */
        const val MIN_PROMPT_CHARS = 8_000

        /** An off-format answer is logged only as far as this, so a runaway reply cannot flood the log. */
        const val MAX_LOGGED_CHARS = 500

        val SYSTEM_PROMPT = """
            You are the memory consolidation agent. Your only job is to reorganise one type of a
            user's long-term memory store into fewer, denser entries without losing information.

            You have no tools. Your entire contribution is one JSON object matching the required output
            schema, and a separate program validates it, checks it against the store and writes it.

            Rules:
            - Merge only entries that describe the same subject or genuinely overlapping facts.
              Never merge unrelated topics just to reduce the count.
            - Keep every distinct, still-true fact. Do not invent facts. When entries conflict,
              prefer the most recently updated one and mention the conflict in the merged body.
            - A cluster must contain at least 2 source entries. An entry with no sibling is not
              written: name it in 'leftoverDocIds' instead.
            - One entry may appear in at most one cluster. Never repeat a docId across clusters.
            - Cite sources by docId, exactly as printed in the prompt. For each cluster,
              'sourceDocIds' is the full absorbed set and 'sectionRefs' maps a heading/anchor in the
              merged body to the docIds it came from; the writer inserts the inline source markers.
            - Never invent a docId, and never report a batch as empty when two of its entries in fact
              describe the same subject.
            - Respect the archive budget reported in the user message. If the budget is smaller than
              the number of clusters, put the most valuable clusters first; the rest are reported as
              deferred.
            - mergedDescription is one line; mergedContent stays inside the body size limit the writer
              enforces, so split rather than overflow it.
        """.trimIndent()
    }
}
