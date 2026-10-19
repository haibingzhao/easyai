package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * Mutable counters for one consolidation run. The writer fills in what it actually did against the
 * store, and [com.easy.easyai.core.memory.MemoryConsolidationAgent] fills in what it got back from the
 * model, so the numbers reflect EasyRAG rather than what the model claimed in its prose.
 */
class MemoryConsolidationRunStats {
    val mergedWritten = AtomicInteger()

    /** Sources archived by a committed cluster. */
    val archived = AtomicInteger()

    /**
     * Sources a committed cluster could not archive: the merged document is already stored, so each of
     * these is still an active duplicate of it. Kept apart from [archived] because the gap is the part
     * a reader has to act on, and the store only ever explains it per id in the log.
     */
    val archiveShortfall = AtomicInteger()

    /** Sources a *previewed* cluster would archive; kept apart from [archived] so a dry run reports
     * zero, while still consuming the budget the real run would have consumed. */
    val plannedArchives = AtomicInteger()
    val skippedSingletons = AtomicInteger()
    val skippedOverCap = AtomicInteger()

    /**
     * Written clusters whose body came back over the target size the prompt asked for. Reported apart
     * from a rejection on purpose: these are stored and usable, they just did not distill as far as the
     * key-points budget, and the number is what tells a reader the sources were too many for one entry.
     */
    val overTarget = AtomicInteger()

    /**
     * Proposed clusters dropped by the grouping validator for exceeding the per-cluster body budget even
     * after trimming, or lost to a repeated docId. They never reach the merge pass, so their sources stay
     * active and untouched — the only signal that grouping asked for something unwritable.
     */
    val droppedGroups = AtomicInteger()

    /** Clusters the grouping pass proposed that survived validation, whether or not a body was written. */
    val clustersProposed = AtomicInteger()

    /** Batches whose answer could not be applied: a first answer that was unusable, or an unusable re-ask. */
    val rejectedBatches = AtomicInteger()

    /**
     * Batches whose model call itself failed, so there was no answer to judge. Kept apart from
     * [rejectedBatches] on purpose: blaming the model for a call that never reached it sends the
     * reader looking at prompts while the run is broken in the transport.
     */
    val llmFailures = AtomicInteger()

    /** Why the first model call failed. Kept once — a systemic break repeats identically per batch. */
    val firstLlmFailure = AtomicReference<String?>(null)

    /** Sources the model neither merged nor declared as leftovers. */
    val uncoveredSources = AtomicInteger()
    val clusters = CopyOnWriteArrayList<Map<String, Any?>>()

    /** Clusters the grouping pass proposed and validated, in the order they were accepted. */
    val proposed = CopyOnWriteArrayList<ProposedCluster>()

    /** A batch the model answered badly: dropped, and its [sourceCount] entries stay unaccounted for. */
    fun lostToFormat(sourceCount: Int) {
        rejectedBatches.incrementAndGet()
        uncoveredSources.addAndGet(sourceCount)
    }

    /**
     * A batch whose model call never returned: [sourceCount] entries are unaccounted for, but the
     * model is not what lost them.
     */
    fun lostToCallFailure(reason: String, sourceCount: Int) {
        llmFailures.incrementAndGet()
        firstLlmFailure.compareAndSet(null, reason)
        uncoveredSources.addAndGet(sourceCount)
    }

    fun plan(): List<Map<String, Any?>> = clusters.toList()
}

/**
 * One validated cluster from the grouping pass, waiting for the merge pass to write its body.
 *
 * It carries sizes rather than text on purpose: the merge pass decides how many of these fit into one
 * prompt from [sourceChars] alone, and only fetches the bodies of the clusters it actually asks about.
 */
data class ProposedCluster(
    val title: String,
    val sourceDocIds: List<String>,
    val sourceChars: Int
)

/**
 * The target slice and bounds of one consolidation run, handed to its [MemoryConsolidationWriter].
 */
class MemoryConsolidationRunState(
    val type: MemoryType,
    val scope: MemoryScope,
    val owner: MemoryOwnerContext,
    val dryRun: Boolean,
    val maxArchives: Int,
    val stats: MemoryConsolidationRunStats = MemoryConsolidationRunStats()
) {

    /** Sources already absorbed by a cluster of this run; one source may only be absorbed once. */
    private val claimedIds = ConcurrentHashMap.newKeySet<String>()

    /**
     * Sources already placed in a proposed cluster. Grouping runs over several prompts, and one source
     * offered to two of them would mean two merged documents claiming the same archived source — the
     * second archive re-pointing `merged_into` and leaving the first set of `ref_ids` dangling.
     */
    private val groupedIds = ConcurrentHashMap.newKeySet<String>()

    fun remainingArchives(): Int =
        (maxArchives - stats.archived.get() - stats.plannedArchives.get()).coerceAtLeast(0)

    /** The subset of [docIds] no cluster of this run has absorbed yet. */
    fun unclaimed(docIds: List<String>): List<String> = docIds.filterNot { claimedIds.contains(it) }

    /** Mark [docIds] absorbed by this run, so a later cluster cannot archive them a second time. */
    fun claim(docIds: Collection<String>) {
        claimedIds.addAll(docIds)
    }

    /** The subset of [docIds] no proposed cluster of this run has claimed yet. */
    fun ungrouped(docIds: List<String>): List<String> = docIds.filterNot { groupedIds.contains(it) }

    /** Mark [docIds] as belonging to an accepted cluster of this run's grouping pass. */
    fun claimGrouped(docIds: Collection<String>) {
        groupedIds.addAll(docIds)
    }
}
