package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mutable counters for one consolidation run. The writer is the only one who increments them — from
 * what it actually did against the store — so the numbers reflect what landed in EasyRAG rather than
 * what the model claimed in its prose.
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

    /** Batches whose JSON was unusable (missing, truncated, or not conforming to the schema). */
    val rejectedBatches = AtomicInteger()

    /** Sources the model neither merged nor declared as leftovers. */
    val uncoveredSources = AtomicInteger()
    val clusters = CopyOnWriteArrayList<Map<String, Any?>>()

    fun plan(): List<Map<String, Any?>> = clusters.toList()
}

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

    fun remainingArchives(): Int =
        (maxArchives - stats.archived.get() - stats.plannedArchives.get()).coerceAtLeast(0)

    /** The subset of [docIds] no cluster of this run has absorbed yet. */
    fun unclaimed(docIds: List<String>): List<String> = docIds.filterNot { claimedIds.contains(it) }

    /** Mark [docIds] absorbed by this run, so a later cluster cannot archive them a second time. */
    fun claim(docIds: Collection<String>) {
        claimedIds.addAll(docIds)
    }
}
