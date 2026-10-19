package com.easy.easyai.core.memory

import com.easy.easyai.api.model.SamplingIntent
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.ErrorEvent
import com.easy.easyai.core.memory.consolidation.CONSOLIDATION_GROUPING_SCHEMA
import com.easy.easyai.core.memory.consolidation.CONSOLIDATION_MERGE_SCHEMA
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationGroupingOutput
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationMergeOutput
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunHandle
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunLifecycle
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunState
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunStats
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationWriter
import com.easy.easyai.core.memory.consolidation.ProposedCluster
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.core.validation.JsonExtractor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Outcome of one consolidation run for a single `(slice, type)`.
 *
 * @property activeBefore active entries of that type in the slice before the run
 * @property clustersProposed clusters the grouping pass proposed and its validator accepted
 * @property mergedWritten merged documents written (0 in a dry run)
 * @property archived source documents archived into a merged document (0 in a dry run)
 * @property archiveShortfall sources a written cluster failed to archive, so they are still live
 *   duplicates of the merged document above them
 * @property skippedSingletons clusters dropped because they had fewer than 2 usable sources
 * @property skippedOverCap clusters deferred because the per-run archive budget ran out
 * @property droppedGroups clusters the grouping validator refused outright: too large to distill even
 *   after trimming their tail
 * @property overTarget written bodies that came back over the key-points target while staying inside the
 *   hard cap — stored, but a sign the clusters were too big for one entry
 * @property rejectedBatches prompts whose answer could not be applied at all: a first answer that was
 *   unusable, or a re-ask that came back unusable after the prompt had already been part-applied (the
 *   applied part stays applied — this counts prompts, not work lost)
 * @property llmFailures prompts whose model call itself failed, so the model never got to answer.
 *   Reported apart from [rejectedBatches] because the fix lives in the transport, not in the prompt
 * @property llmFailureReason why the first such call failed, as the model client reported it
 * @property uncoveredDocIds sources the model neither clustered nor declared as leftovers
 * @property plan per-cluster detail; a dry run reports the grouping plan, a real run the cluster outcomes
 */
data class ConsolidationReport(
    val type: MemoryType,
    val scope: MemoryScope,
    val activeBefore: Int,
    val clustersProposed: Int = 0,
    val mergedWritten: Int = 0,
    val archived: Int = 0,
    val archiveShortfall: Int = 0,
    val skippedSingletons: Int = 0,
    val skippedOverCap: Int = 0,
    val droppedGroups: Int = 0,
    val overTarget: Int = 0,
    val rejectedBatches: Int = 0,
    val llmFailures: Int = 0,
    val llmFailureReason: String? = null,
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
 * Runs a consolidation pass through a real, persisted Agent Loop, in two passes over one session.
 *
 * **Grouping** is asked first: one prompt per budget-sized slice of the *index* — docId, name, blurb,
 * level and stored size, never a body — answered by listing which docIds belong together. Membership is
 * the part that needs the widest possible view and costs the model almost nothing to answer, which is
 * exactly why it is its own pass: the old single prompt had to re-emit every merged body, so its size was
 * bound by the output cap, and entries that landed in different prompts could never be compared at all.
 *
 * **Merging** then rewrites a few clusters per prompt. That is the expensive side: an answer re-emits
 * whole merged bodies, so the prompt budget is bounded by what the model can emit inside its output cap,
 * not by how much context it can read.
 *
 * Both passes share one hidden session (`session.system_kind = async_task`, which is what filters it out
 * of Chat History); each turn swaps that turn's instructions and output schema, so the task row keeps a
 * single `sessionId` and a retry still finds every earlier attempt in the same transcript. Everything the
 * model does is persisted there, and this class owns every write through [MemoryConsolidationWriter], so
 * a model that answers with prose instead of JSON loses one prompt instead of silently skipping the work.
 *
 * A preview stops after grouping: what a preview can honestly promise is which entries would be merged,
 * and the request waiting on it synchronously should not pay for bodies nobody shows it.
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

    private val writer = MemoryConsolidationWriter(store, settings)

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

        val index = store.listActiveIndex(scope, owner, type).distinctBy { it.docId }
        // An entry at the depth ceiling is a finished digest, not material: offering it again is how a
        // slice that gains two memories a day spends every run distilling yesterday's summary.
        val eligible = index.filter { it.level < settings.maxLevel }
        if (eligible.size < 2) {
            logger.debug(
                "Memory consolidation skipped for {} {}: {} active, {} under the depth ceiling of {}",
                scope, type.dirName, index.size, eligible.size, settings.maxLevel
            )
            return ConsolidationReport(
                type = type,
                scope = scope,
                activeBefore = index.size,
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
            customInstructions = GROUPING_SYSTEM_PROMPT,
            // Curation merges and rewrites stored facts; two runs over the same cluster should not
            // disagree about what was kept.
            samplingIntent = SamplingIntent.DETERMINISTIC,
            outputSchema = CONSOLIDATION_GROUPING_SCHEMA,
            noTools = true
        )
        val runState = MemoryConsolidationRunState(
            type = type,
            scope = scope,
            owner = owner,
            dryRun = dryRun,
            maxArchives = settings.maxArchivesPerRun.coerceAtLeast(1),
            stats = MemoryConsolidationRunStats()
        )
        // [noTools] is the point: the run's only output channel is the schema-conforming answer, and this
        // class is the only writer. A chat turn over the same session therefore cannot reach the store
        // either — and the request body no longer contradicts the system prompt it ships with.
        val session = sessionManager.getOrCreateSession(baseContext, modelConfig, factory)

        val callCounts: PromptCounts
        var handle: MemoryConsolidationRunHandle? = null
        try {
            handle = lifecycle?.begin(sid, userId)
            logger.info(
                "Memory consolidation start: scope={}, type={}, entries={}, eligible={}, dryRun={}, session={}",
                scope, type.dirName, index.size, eligible.size, dryRun, sid
            )
            callCounts = runBothPasses(session, eligible, runState)
        } finally {
            handle?.end(session.lastEndReason)
        }

        val stats = runState.stats
        val report = ConsolidationReport(
            type = type,
            scope = scope,
            activeBefore = index.size,
            clustersProposed = stats.clustersProposed.get(),
            mergedWritten = stats.mergedWritten.get(),
            archived = stats.archived.get(),
            archiveShortfall = stats.archiveShortfall.get(),
            skippedSingletons = stats.skippedSingletons.get(),
            skippedOverCap = stats.skippedOverCap.get(),
            droppedGroups = stats.droppedGroups.get(),
            overTarget = stats.overTarget.get(),
            rejectedBatches = stats.rejectedBatches.get(),
            llmFailures = stats.llmFailures.get(),
            llmFailureReason = stats.firstLlmFailure.get(),
            uncoveredDocIds = stats.uncoveredSources.get(),
            dryRun = dryRun,
            sessionId = sid,
            plan = stats.plan()
        )
        logger.info(
            "Memory consolidation done: scope={}, type={}, groupingPrompts={}, mergePrompts={}, proposed={}, " +
                "merged={}, archived={}, archiveShortfall={}, overTarget={}, skippedSingletons={}, " +
                "skippedOverCap={}, droppedGroups={}, rejectedBatches={}, llmFailures={}, uncoveredSources={}",
            scope, type.dirName, callCounts.grouping, callCounts.merge, report.clustersProposed,
            report.mergedWritten, report.archived, report.archiveShortfall, report.overTarget,
            report.skippedSingletons, report.skippedOverCap, report.droppedGroups, report.rejectedBatches,
            report.llmFailures, report.uncoveredDocIds
        )
        return report
    }

    /** Both passes over one session; the returned counts are for the log line, not for the report. */
    private suspend fun runBothPasses(
        session: ChatSession,
        eligible: List<MemoryIndexEntry>,
        runState: MemoryConsolidationRunState
    ): PromptCounts {
        val groups = batchIndexByChars(eligible)
        groups.forEachIndexed { position, batch ->
            runGroupingTurn(session, batch, position + 1, groups.size, runState)
        }
        if (runState.dryRun) return PromptCounts(groups.size, 0)
        val proposed = runState.stats.proposed.toList()
        if (proposed.isEmpty()) {
            logger.debug(
                "Grouping proposed nothing for {} {}; merge pass skipped",
                runState.scope, runState.type.dirName
            )
            return PromptCounts(groups.size, 0)
        }
        val packs = packClusters(proposed)
        packs.forEachIndexed { position, pack ->
            runMergeTurn(session, pack, position + 1, packs.size, runState)
        }
        return PromptCounts(groups.size, packs.size)
    }

    /**
     * One grouping prompt, plus the one bounded re-ask its gaps earn.
     *
     * The re-ask re-sends the rows it is asking about. A turn of this session is answered on its own text
     * — nothing an earlier prompt showed comes back with it — so a bare docId list would be a question
     * about entries the model has never seen, which is exactly how the old re-ask wasted a call.
     */
    private suspend fun runGroupingTurn(
        session: ChatSession,
        batch: List<MemoryIndexEntry>,
        position: Int,
        batchCount: Int,
        runState: MemoryConsolidationRunState
    ) {
        val stats = runState.stats
        val docIds = batch.map { it.docId }
        when (
            val first = ask(
                session,
                PHASE_GROUPING,
                groupingPrompt(batch, position, batchCount, runState),
                position,
                batchCount,
                GROUPING_TYPE
            )
        ) {
            is BatchAnswer.CallFailed -> stats.lostToCallFailure(first.reason, docIds.size)
            BatchAnswer.BadFormat -> stats.lostToFormat(docIds.size)
            is BatchAnswer.Applied -> {
                val uncovered = writer.planGroups(batch, first.output, runState).uncoveredDocIds
                if (uncovered.isEmpty()) return
                val byId = batch.associateBy { it.docId }
                val missing = uncovered.mapNotNull { byId[it] }
                // The gap can be the whole batch, so the re-ask is sized like the question it follows
                // rather than sent as one prompt that cannot fit what it asks about.
                batchIndexByChars(missing).forEach { part ->
                    askAndPlanGroups(session, part, position, batchCount, runState, stats, reask = true)
                }
            }
        }
    }

    private suspend fun askAndPlanGroups(
        session: ChatSession,
        part: List<MemoryIndexEntry>,
        position: Int,
        batchCount: Int,
        runState: MemoryConsolidationRunState,
        stats: MemoryConsolidationRunStats,
        reask: Boolean
    ) {
        val ids = part.map { it.docId }
        when (
            val answer = ask(
                session,
                PHASE_GROUPING,
                if (reask) groupingReaskPrompt(part, position, batchCount, runState)
                else groupingPrompt(part, position, batchCount, runState),
                position,
                batchCount,
                GROUPING_TYPE,
                reask
            )
        ) {
            is BatchAnswer.CallFailed -> stats.lostToCallFailure(answer.reason, ids.size)
            BatchAnswer.BadFormat -> stats.lostToFormat(ids.size)
            is BatchAnswer.Applied -> {
                // Counted only from the gap that survives the re-ask: a recovered batch must not still
                // report what its first answer missed.
                val second = writer.planGroups(part, answer.output, runState)
                stats.uncoveredSources.addAndGet(second.uncoveredDocIds.size)
            }
        }
    }

    /** One merge prompt over whole clusters, plus the bounded re-ask for the clusters it left open. */
    private suspend fun runMergeTurn(
        session: ChatSession,
        pack: List<ProposedCluster>,
        position: Int,
        packCount: Int,
        runState: MemoryConsolidationRunState
    ) {
        val stats = runState.stats
        val sources = fetchSources(pack, runState)
        val docIds = sources.mapNotNull { it.id }
        if (docIds.size < 2) {
            // Nothing live to merge: not a lost prompt, just work that was already done elsewhere.
            logger.debug("Merge prompt {} has no live sources left; nothing to ask", position)
            stats.uncoveredSources.addAndGet(docIds.size)
            return
        }
        when (
            val first = ask(
                session,
                PHASE_MERGE,
                mergePrompt(pack, sources, position, packCount, runState),
                position,
                packCount,
                MERGE_TYPE
            )
        ) {
            is BatchAnswer.CallFailed -> stats.lostToCallFailure(first.reason, docIds.size)
            BatchAnswer.BadFormat -> stats.lostToFormat(docIds.size)
            is BatchAnswer.Applied -> {
                val uncovered = writer.writeBatch(docIds, first.output, runState).uncoveredDocIds
                if (uncovered.isEmpty()) return
                reaskMerges(uncovered, sources, pack, session, position, packCount, runState, stats)
            }
        }
    }

    /**
     * Re-ask the clusters a merge prompt left open, with their bodies attached.
     *
     * A cluster is the smallest unit this pass can answer, so the surviving clusters are rebuilt around
     * only the sources still unaccounted for — sources an earlier answer already absorbed would otherwise
     * come back as a second claim on the same document.
     */
    private suspend fun reaskMerges(
        uncovered: List<String>,
        sources: List<MemoryEntry>,
        pack: List<ProposedCluster>,
        session: ChatSession,
        position: Int,
        packCount: Int,
        runState: MemoryConsolidationRunState,
        stats: MemoryConsolidationRunStats
    ) {
        val byId = sources.associateBy { it.id }
        val missingIds = uncovered.toSet()
        val missing = uncovered.mapNotNull { byId[it] }
        if (missing.isEmpty()) {
            stats.uncoveredSources.addAndGet(uncovered.size)
            return
        }
        val rebuilt = pack.map { cluster ->
            val remaining = cluster.sourceDocIds.filter { it in missingIds }
            cluster.copy(
                sourceDocIds = remaining,
                sourceChars = remaining.sumOf { byId[it]?.content?.length ?: 0 }
            )
        }.filter { it.sourceDocIds.size >= 2 }
        if (rebuilt.isEmpty()) {
            // Only single survivors: nothing this pass can answer as a cluster, so they stay active.
            stats.uncoveredSources.addAndGet(missingIds.size)
            return
        }
        packClusters(rebuilt).forEach { part ->
            val ids = part.flatMap { it.sourceDocIds }
            val partSources = ids.mapNotNull { byId[it] }
            when (
                val retry = ask(
                    session,
                    PHASE_MERGE,
                    mergeReaskPrompt(part, partSources, position, packCount, runState),
                    position,
                    packCount,
                    MERGE_TYPE,
                    reask = true
                )
            ) {
                is BatchAnswer.CallFailed -> stats.lostToCallFailure(retry.reason, ids.size)
                BatchAnswer.BadFormat -> stats.lostToFormat(ids.size)
                is BatchAnswer.Applied -> {
                    val second = writer.writeBatch(ids, retry.output, runState)
                    stats.uncoveredSources.addAndGet(second.uncoveredDocIds.size)
                }
            }
        }
    }

    private suspend fun fetchSources(pack: List<ProposedCluster>, runState: MemoryConsolidationRunState): List<MemoryEntry> =
        store.findByIds(pack.flatMap { it.sourceDocIds }.distinct(), runState.scope, runState.owner)
            .filter { !it.archived }

    /**
     * Run one prompt in the session and deserialize the schema-conforming answer it should produce.
     *
     * The turn's contract is set here rather than by the callers so a pass can never ask its question with
     * the other pass's schema attached. "There is no answer" arrives in two flavours that must not share a
     * counter: the model answered something unusable, versus the call never reaching the model at all.
     */
    private suspend fun <T> ask(
        session: ChatSession,
        phase: String,
        prompt: String,
        position: Int,
        count: Int,
        outputType: Class<T>,
        reask: Boolean = false
    ): BatchAnswer<T> {
        session.updateTurnOutputContract(
            customInstructions = if (phase == PHASE_MERGE) MERGE_SYSTEM_PROMPT else GROUPING_SYSTEM_PROMPT,
            outputSchema = if (phase == PHASE_MERGE) CONSOLIDATION_MERGE_SCHEMA else CONSOLIDATION_GROUPING_SCHEMA
        )
        val metadata = buildMap {
            put(PHASE_KEY, phase)
            put(BATCH_KEY, "$position/$count")
            if (reask) put(REASK_KEY, "true")
        }
        val stream = session.promptWithHistory(
            listOf(UserMessage(content = listOf(TextContent(prompt)), metadata = metadata))
        )
        val text = stream.result().lastOrNull()?.text().orEmpty()
        // A run the schema check gave up on carries no answer, only the text it failed with. This
        // caller writes stored memories from that payload, so a stalled reply has to cost a prompt
        // rather than become a merge the model never approved.
        if (session.lastEndReason == AgentCompletionCheck.END_REASON_STALLED) {
            logger.warn("Consolidation prompt stopped by its own completion check; discarding the answer")
            return BatchAnswer.BadFormat
        }
        // The loop parks a call that threw here, with no assistant message behind it. Blaming the
        // model's formatting for that sent readers tuning prompts while the request never left the
        // process, so the reason is carried out with the counter.
        if (session.lastEndReason == END_REASON_LLM_ERROR) {
            val reason = stream.asFlow().filterIsInstance<ErrorEvent>().firstOrNull()?.error?.message
            logger.warn("Consolidation prompt never reached the model: {}", reason)
            return BatchAnswer.CallFailed(reason?.take(MAX_LOGGED_REASON) ?: "the model call failed without a message")
        }
        val json = JsonExtractor.extract(text)
        if (json == null) {
            logger.warn("Consolidation prompt produced no JSON: {}", text.take(MAX_LOGGED_CHARS))
            return BatchAnswer.BadFormat
        }
        return try {
            BatchAnswer.Applied(SharedObjectMapper.instance.readValue(json, outputType))
        } catch (e: Exception) {
            // The loop gives up on its last invalid answer, so a failure here is expected occasionally
            // and must cost one prompt, not the run.
            logger.warn("Consolidation prompt JSON does not match the schema: {}", e.message)
            BatchAnswer.BadFormat
        }
    }

    /**
     * Cut one slice's index rows into grouping prompts by character budget, ordered by name first so
     * that when a slice does overflow one prompt, the entries most likely to be about the same subject
     * travel together instead of being scattered by insertion order.
     *
     * A row is a name, a blurb and framing — never a body — so one of these prompts holds far more
     * entries than a merge prompt could, which is the whole point: comparison needs breadth.
     */
    internal fun batchIndexByChars(entries: List<MemoryIndexEntry>): List<List<MemoryIndexEntry>> {
        val budget = settings.maxGroupingPromptChars.coerceAtLeast(MIN_GROUPING_PROMPT_CHARS)
        val sorted = entries.sortedWith(compareBy({ it.type.dirName }, { it.name }, { it.docId }))
        val batches = mutableListOf<List<MemoryIndexEntry>>()
        var batch = mutableListOf<MemoryIndexEntry>()
        var chars = PROMPT_HEADER_CHARS
        for (entry in sorted) {
            val cost = entry.name.length + entry.blurb.length + ENTRY_INDEX_FRAME_CHARS
            if (batch.isNotEmpty() && chars + cost > budget) {
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

    /**
     * Pack proposed clusters into merge prompts: at most [MemoryConsolidationSettings.clustersPerMergeCall]
     * and at most [MemoryConsolidationSettings.maxMergePromptCharsPerCall] of source bodies per prompt.
     *
     * A cluster is never split across prompts — half a cluster is an answer the writer cannot apply — so
     * one that alone exceeds the budget travels by itself, and it is the model's problem to refuse it.
     */
    internal fun packClusters(clusters: List<ProposedCluster>): List<List<ProposedCluster>> {
        val budget = settings.maxMergePromptCharsPerCall.coerceAtLeast(MIN_MERGE_PROMPT_CHARS)
        val perCall = settings.clustersPerMergeCall.coerceAtLeast(1)
        val packs = mutableListOf<List<ProposedCluster>>()
        var pack = mutableListOf<ProposedCluster>()
        var chars = PROMPT_HEADER_CHARS
        for (cluster in clusters) {
            val cost = cluster.sourceChars + MERGE_CLUSTER_FRAME_CHARS
            if (pack.isNotEmpty() && (pack.size >= perCall || chars + cost > budget)) {
                packs += pack.toList()
                pack = mutableListOf()
                chars = PROMPT_HEADER_CHARS
            }
            pack += cluster
            chars += cost
        }
        if (pack.isNotEmpty()) packs += pack.toList()
        return packs
    }

    private fun groupingPrompt(
        entries: List<MemoryIndexEntry>,
        position: Int,
        count: Int,
        runState: MemoryConsolidationRunState
    ): String = buildString {
        appendHeader(runState, "grouping", position, count, entries.size, archiveBudget = false)
        appendLine("Group these entries only. Their bodies are not shown: decide from name, blurb, level")
        appendLine("and stored size, and expect to be corrected once the bodies are read.")
        appendLine("A group needs at least 2 and at most ${settings.maxSourcesPerGroup} entries whose bodies")
        appendLine("together stay under ${settings.maxGroupSourceChars} characters — that is the size of the")
        appendLine("merged entry you will later be asked to distill. An oversized group is refused, not")
        appendLine("squeezed: leave those entries to 'leftoverDocIds'.")
        appendLine("Every docId of this prompt must appear in exactly one group or in 'leftoverDocIds'.")
        appendLine()
        appendIndexRows(entries)
    }

    private fun groupingReaskPrompt(
        entries: List<MemoryIndexEntry>,
        position: Int,
        count: Int,
        runState: MemoryConsolidationRunState
    ): String = buildString {
        appendHeader(runState, "grouping re-ask", position, count, entries.size, archiveBudget = false)
        appendLine("These are the entries of prompt $position/$count that appeared in no group and were")
        appendLine("not listed as leftovers. Their rows are repeated here because each prompt is answered")
        appendLine("on its own text, not on anything an earlier prompt showed.")
        appendLine("Answer again with the full JSON object for just these entries: group the ones that")
        appendLine("belong together, and list every entry you judge unmergeable in 'leftoverDocIds'.")
        appendLine()
        appendIndexRows(entries)
    }

    private fun mergePrompt(
        pack: List<ProposedCluster>,
        sources: List<MemoryEntry>,
        position: Int,
        count: Int,
        runState: MemoryConsolidationRunState
    ): String = buildString {
        appendHeader(runState, "merging", position, count, sources.size, archiveBudget = true)
        appendLine("Clusters proposed by the grouping pass, each with the full bodies of its sources.")
        appendLine("Write one merged memory per cluster, or refuse a cluster after reading it by listing")
        appendLine("every one of its docIds in 'unmergedDocIds'. Silence is read as a lost answer.")
        appendLine("mergedContent is a distilled digest of key points under headings, in this order: what")
        appendLine("happened and when, the reasoning or conditions behind it, and the conclusion to take.")
        appendLine("Target ${MemoryContentLimits.CONTENT_TARGET_CHARS} characters; the program rejects an")
        appendLine("oversized body, so distill further rather than overflow it.")
        appendLine("Every docId of this prompt must appear in exactly one cluster or in 'unmergedDocIds'.")
        appendLine()
        appendClusterBlocks(pack, sources)
    }

    private fun mergeReaskPrompt(
        pack: List<ProposedCluster>,
        sources: List<MemoryEntry>,
        position: Int,
        count: Int,
        runState: MemoryConsolidationRunState
    ): String = buildString {
        appendHeader(runState, "merging re-ask", position, count, sources.size, archiveBudget = true)
        appendLine("These clusters of prompt $position/$count still have docIds in neither a written")
        appendLine("cluster nor 'unmergedDocIds'. Their bodies are repeated here because each prompt is")
        appendLine("answered on its own text, not on anything an earlier prompt showed.")
        appendLine("Answer again with the full JSON object for just these clusters.")
        appendLine()
        appendClusterBlocks(pack, sources)
    }

    private fun StringBuilder.appendHeader(
        runState: MemoryConsolidationRunState,
        phase: String,
        position: Int,
        count: Int,
        entryCount: Int,
        archiveBudget: Boolean
    ) {
        appendLine("Memory consolidation run.")
        appendLine("Phase: $phase")
        appendLine("Type: ${runState.type.dirName}")
        appendLine("Slice: ${runState.scope.name}")
        appendLine("Prompt: $position/$count")
        appendLine("Entries in this prompt: $entryCount")
        if (archiveBudget) appendLine("Archive budget remaining: ${runState.remainingArchives()}")
    }

    private fun StringBuilder.appendIndexRows(entries: List<MemoryIndexEntry>) {
        appendLine("<memoryIndex>")
        entries.forEach { entry ->
            appendLine(
                "<entry docId=\"${escapeAttribute(entry.docId)}\" name=\"${escapeAttribute(entry.name)}\" " +
                    "type=\"${entry.type.dirName}\" level=\"${entry.level}\" chars=\"${entry.contentChars}\">"
            )
            appendLine(neutralizeTag(entry.blurb, "entry"))
            appendLine("</entry>")
        }
        appendLine("</memoryIndex>")
    }

    private fun StringBuilder.appendClusterBlocks(pack: List<ProposedCluster>, sources: List<MemoryEntry>) {
        val byId = sources.associateBy { it.id }
        appendLine("<clusters>")
        pack.forEach { cluster ->
            appendLine("<cluster title=\"${escapeAttribute(cluster.title)}\">")
            cluster.sourceDocIds.forEach { docId ->
                val entry = byId[docId] ?: return@forEach
                appendLine(
                    "<memory docId=\"${escapeAttribute(docId)}\" name=\"${escapeAttribute(entry.name)}\" " +
                        "type=\"${entry.type.dirName}\" level=\"${entry.level}\" " +
                        "updated=\"${escapeAttribute(entry.updated?.toString()) ?: "unknown"}\" " +
                        "maturity=\"${entry.maturity?.apiName ?: "unset"}\">"
                )
                appendLine("description: ${entry.description}")
                appendLine(neutralizeTag(entry.content, "memory"))
                appendLine("</memory>")
            }
            appendLine("</cluster>")
        }
        appendLine("</clusters>")
    }

    /** Attribute values are interpolated into a quoted XML-ish attribute, so quotes must not end it. */
    private fun escapeAttribute(value: String?): String? = value?.replace("&", "&amp;")
        ?.replace("\"", "&quot;")
        ?.replace("<", "&lt;")
        ?.replace(">", "&gt;")

    /** A stored body that happens to carry the framing tag would otherwise end the element early. */
    private fun neutralizeTag(content: String, tag: String): String =
        content.replace("</$tag", "<//$tag")

    private suspend fun resolveCurationConfig(owners: Collection<String>) =
        auxResolver?.resolveConfig(owners, AuxModelTask.MEMORY_CURATION)
            ?: throw IllegalStateException(
                "Memory curation model is not configured; set it in Settings → Task Models"
            )

    /** How many prompts each pass spent, for the run's log line. */
    private data class PromptCounts(val grouping: Int, val merge: Int)

    private companion object {
        const val AGENT_ID = "memory-consolidation"

        /** Transcript markers: what lets the chat view split a two-pass run into its two passes. */
        const val PHASE_KEY = "consolidationPhase"
        const val BATCH_KEY = "consolidationBatch"
        const val REASK_KEY = "consolidationReask"
        const val PHASE_GROUPING = "grouping"
        const val PHASE_MERGE = "merge"

        /** Deserialization targets, kept as tokens so one generic [ask] serves both passes. */
        val GROUPING_TYPE: Class<MemoryConsolidationGroupingOutput> =
            MemoryConsolidationGroupingOutput::class.java
        val MERGE_TYPE: Class<MemoryConsolidationMergeOutput> = MemoryConsolidationMergeOutput::class.java

        /** Fixed instruction block of one prompt, measured generously. */
        const val PROMPT_HEADER_CHARS = 800

        /** Per-entry index framing (`<entry …>` tag, blurb line, closing tag). */
        const val ENTRY_INDEX_FRAME_CHARS = 80

        /** Per-cluster framing around the bodies it carries. */
        const val MERGE_CLUSTER_FRAME_CHARS = 200

        /** A grouping budget below a handful of rows would emit near-empty prompts. */
        const val MIN_GROUPING_PROMPT_CHARS = 20_000

        /** A merge budget below one hard-capped body would emit empty prompts. */
        const val MIN_MERGE_PROMPT_CHARS = 8_000

        /** An off-format answer is logged only as far as this, so a runaway reply cannot flood the log. */
        const val MAX_LOGGED_CHARS = 500

        /**
         * The end reason [com.easy.easyai.core.agent.AgentRunner] parks a run whose model call threw.
         * Distinct from a bad answer: nothing was ever asked of the model.
         */
        const val END_REASON_LLM_ERROR = "error"

        /** How much of a model-client failure travels into the report; the rest stays in the log. */
        const val MAX_LOGGED_REASON = 300

        val GROUPING_SYSTEM_PROMPT = """
            You are the memory consolidation agent's grouping pass. You decide which entries of one
            memory type belong together, and nothing else. You are not shown entry bodies and you write
            nothing: a separate program validates your clusters, then reads the bodies and merges them.

            You have no tools. Your entire contribution is one JSON object matching the required output
            schema.

            Rules:
            - Group only entries that plainly describe the same subject or overlapping facts, judged from
              name, blurb, type, level and stored size. Never group unrelated topics to reduce the count.
            - A group needs at least 2 docIds and must respect the entry-count and combined-size limits
              reported in the user message: the merged entry it would become has a body budget, and an
              oversized group is refused rather than squeezed.
            - One docId may appear in at most one group. Never repeat a docId.
            - Never invent a docId; cite exactly the ones printed in the prompt.
            - Prefer folding level-0 entries into an existing higher-level entry on the same subject.
              Do not group several higher-level digests together merely to reduce the count.
            - An entry with no sibling is not grouped: name it in 'leftoverDocIds'.
            - If nothing in this prompt belongs together, return an empty 'groups' — never prose.
        """.trimIndent()

        val MERGE_SYSTEM_PROMPT = """
            You are the memory consolidation agent's merging pass. You are given whole clusters with the
            full bodies of their source entries. For each cluster, write one merged memory, or refuse it.

            You have no tools. Your entire contribution is one JSON object matching the required output
            schema, and a separate program validates it, checks it against the store and writes it.

            Rules:
            - mergedContent is a distilled digest of key points, not a concatenation. Cover it under
              headings, in this order: what happened and when, the reasoning or conditions behind it, and
              the conclusion or action to take. Keep every distinct still-true fact in some form; do not
              invent facts. When sources conflict, prefer the most recently updated one and note it.
            - Stay inside the character target reported in the prompt. The program rejects an oversized
              body outright, so distill further rather than overflow.
            - mergedDescription is one line.
            - Cite sources by docId. 'sourceDocIds' is the full absorbed set; 'sectionRefs' maps a heading
              or exact anchor phrase of mergedContent to the docIds behind it, and the program inserts the
              inline source markers there.
            - Refusing is allowed and must be explicit: if reading the bodies shows they are about
              different things, list every one of that cluster's docIds in 'unmergedDocIds'. Silence is
              read as a lost answer, not as a refusal.
            - Never invent a docId, and never refuse a cluster whose entries in fact describe the same
              subject.
            - Respect the archive budget reported in the user message: if it is smaller than the number of
              clusters, write the most valuable ones first and refuse the rest.
        """.trimIndent()
    }
}

/** What one prompt came back with, as far as the writer is concerned. */
private sealed interface BatchAnswer<out T> {

    /** The model answered in the required shape. */
    data class Applied<T>(val output: T) : BatchAnswer<T>

    /** The model answered, but not in the required shape. The prompt's own fault. */
    data object BadFormat : BatchAnswer<Nothing>

    /**
     * The call never reached the model, or died before it answered. [reason] is what the model client
     * said, so a broken transport is not reported as a model that will not follow instructions.
     */
    data class CallFailed(val reason: String) : BatchAnswer<Nothing>
}
