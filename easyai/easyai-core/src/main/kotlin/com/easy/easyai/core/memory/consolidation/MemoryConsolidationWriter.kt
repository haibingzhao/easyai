package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.core.memory.MemoryArchiveUpdate
import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryContentLimits
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryIndexEntry
import com.easy.easyai.core.memory.MemoryStore
import org.slf4j.LoggerFactory
import java.time.LocalDate

/**
 * Applies what a consolidation run's two passes proposed to the memory store. This is the sole write
 * path of a run: the model proposes clusters and merged bodies, and every decision to accept one is
 * made here.
 *
 * The shape checks (a cluster needs 2+ sources, bodies inside the caps) came straight from the commit
 * tool this replaced, and the set checks are the ones a JSON Schema cannot express: a docId the model
 * invented, a source another cluster already absorbed, and a source archived by an earlier run all
 * have to be caught against the store. Nothing is deleted — absorbed sources are archived and the
 * merged document references them through `ref_ids`.
 *
 * [planGroups] validates and queues; [writeBatch] writes. They are separate because the grouping pass
 * decides membership from names and sizes alone, and a cluster that cannot survive its source sizes must
 * be stopped there rather than after the run has paid for a merge prompt to discover it.
 */
internal class MemoryConsolidationWriter(
    private val store: MemoryStore,
    private val settings: MemoryConsolidationSettings = MemoryConsolidationSettings()
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Deliberately the only number here: per-batch commit counts duplicate what the run's own counters
     * already sum, and reporting a previewed cluster as "rejected" alongside genuinely rejected ones
     * made the same field mean two incompatible things.
     *
     * @param uncoveredDocIds docIds of the batch that appeared in neither an accepted cluster, nor the
     *   batch's declared leftover set, nor a group this validator has already explained — a refusal, a
     *   budget exhaustion, or a source an earlier cluster took. Those are decisions, and the model has
     *   already answered the rows they contain; only a genuinely unaccounted source is a cue to ask again
     *   *with its rows attached*.
     */
    data class GroupPlanOutcome(val uncoveredDocIds: List<String>)

    /**
     * @param uncoveredDocIds docIds of this merge prompt's clusters that appear in neither a written
     *   cluster nor [MemoryConsolidationMergeOutput.unmergedDocIds]
     */
    data class BatchOutcome(val uncoveredDocIds: List<String>)

    /**
     * Validate one grouping prompt's clusters, queue the survivors for the merge pass, and record the
     * per-cluster numbers a preview shows.
     *
     * Every rule here exists because the grouping model cannot see what it is clustering: a cluster over
     * the entry count or the combined body budget cannot be distilled into the key-points size the merge
     * pass is asked for, so it is trimmed — and dropped outright when trimming leaves fewer than two
     * sources. Nothing is written; a dry run ends here.
     */
    suspend fun planGroups(
        batch: List<MemoryIndexEntry>,
        output: MemoryConsolidationGroupingOutput,
        state: MemoryConsolidationRunState
    ): GroupPlanOutcome {
        val byId = batch.associateBy { it.docId }
        val claimedByModel = mutableSetOf<String>()
        // A group the validator refused for size is a *decision*, not a gap: asking again shows the model
        // the same rows against the same limits and earns the same answer, so its sources are settled for
        // this run rather than counted as unaccounted for. The same holds for a group whose sources an
        // earlier cluster took, and for one the preview ran out of archive budget on.
        val settledByRefusal = mutableSetOf<String>()
        val leftovers = output.leftoverDocIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()

        output.groups.forEachIndexed { index, group ->
            val requested = group.sourceDocIds.map { it.trim() }.filter { byId.containsKey(it) }.distinct()
            if (requested.isEmpty()) return@forEachIndexed
            val unclaimed = state.ungrouped(requested)
            val unknownCount = group.sourceDocIds.count { it.isBlank() || !byId.containsKey(it.trim()) }
            if (unclaimed.size < 2) {
                state.stats.skippedSingletons.incrementAndGet()
                // Sources an earlier cluster already took are accounted for, even when that cluster came
                // from another batch's prompt — re-asking would show the model rows it has answered for.
                // A source still free here stays a gap: two lone ones from two clusters can pair up.
                settledByRefusal += requested.filterNot { it in unclaimed }
                addGroupRow(
                    index, group.title, unclaimed, sumChars(unclaimed, byId), maxLevelOf(unclaimed, byId), state,
                    "skipped: ${requested.size - unclaimed.size} of ${requested.size} sources were already " +
                        "placed in an earlier cluster"
                )
                return@forEachIndexed
            }
            val trimmed = trimToLimits(unclaimed, byId)
            if (trimmed.size < 2) {
                state.stats.droppedGroups.incrementAndGet()
                settledByRefusal += unclaimed
                addGroupRow(
                    index, group.title, unclaimed, sumChars(unclaimed, byId), maxLevelOf(unclaimed, byId), state,
                    "dropped: sources too large for one merged entry"
                )
                return@forEachIndexed
            }
            state.claimGrouped(trimmed)
            claimedByModel += trimmed
            state.stats.clustersProposed.incrementAndGet()
            val title = group.title.trim().ifBlank { "merge-${byId.getValue(trimmed.first()).name}" }
            state.stats.proposed.add(
                ProposedCluster(
                    title = title,
                    sourceDocIds = trimmed,
                    sourceChars = sumChars(trimmed, byId)
                )
            )
            if (unknownCount > 0) {
                logger.debug(
                    "Dropped {} invented docId(s) from grouping cluster '{}'",
                    unknownCount, title
                )
            }
            if (state.dryRun) {
                // A preview consumes the same budget as the write it describes and reports the same
                // refusal: a real run defers a whole cluster that no longer fits, so charging a slice of
                // one here would show a cluster the run would never merge.
                val remaining = state.remainingArchives()
                if (trimmed.size > remaining) {
                    state.stats.skippedOverCap.incrementAndGet()
                    settledByRefusal += trimmed
                    addGroupRow(
                        index, title, trimmed, sumChars(trimmed, byId), maxLevelOf(trimmed, byId), state,
                        "deferred: ${trimmed.size} sources exceed the remaining budget of $remaining"
                    )
                } else {
                    state.claim(trimmed)
                    state.stats.plannedArchives.addAndGet(trimmed.size)
                    addGroupRow(index, title, trimmed, sumChars(trimmed, byId), maxLevelOf(trimmed, byId), state)
                }
            }
        }

        val uncovered = batch.map { it.docId }
            .filter { it.isNotEmpty() && it !in claimedByModel && it !in leftovers && it !in settledByRefusal }
        return GroupPlanOutcome(uncovered)
    }

    /**
     * Write (or, for a dry run, only plan) every cluster of one merge prompt and fold the totals into
     * [state]. A cluster that fails a check is reported and skipped; it never aborts the batch.
     *
     * [BatchOutcome.uncoveredDocIds] is returned rather than counted here: the caller may still be able
     * to recover those sources with one more question, and only it knows whether it did.
     */
    suspend fun writeBatch(
        batchDocIds: List<String>,
        output: MemoryConsolidationMergeOutput,
        state: MemoryConsolidationRunState
    ): BatchOutcome {
        val claimedByModel = mutableSetOf<String>()

        for ((index, cluster) in output.clusters.withIndex()) {
            state.stats.clusters.add(commitCluster(index, cluster, state))
            cluster.sourceDocIds.map { it.trim() }.filter { it.isNotEmpty() }.let { claimedByModel += it }
        }

        val unmerged = output.unmergedDocIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val uncovered = batchDocIds.filter { it.isNotEmpty() && it !in claimedByModel && it !in unmerged }
        return BatchOutcome(uncoveredDocIds = uncovered)
    }

    private suspend fun commitCluster(
        index: Int,
        cluster: ConsolidationCluster,
        state: MemoryConsolidationRunState
    ): Map<String, Any?> {
        val stats = state.stats
        val requested = cluster.sourceDocIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        if (requested.size < 2) {
            stats.skippedSingletons.incrementAndGet()
            return rejectedRow(index, "skipped: needs at least 2 sourceDocIds")
        }
        // One source may be absorbed by exactly one cluster: a second archive would re-point its
        // merged_into and leave the first merged document's ref_ids dangling.
        val unclaimed = state.unclaimed(requested)
        if (unclaimed.size < 2) {
            stats.skippedSingletons.incrementAndGet()
            return rejectedRow(
                index,
                "skipped: ${requested.size - unclaimed.size} of ${requested.size} sources " +
                    "were already absorbed by an earlier cluster of this run",
                title = cluster.mergedTitle,
                sourceDocIds = requested
            )
        }
        val title = cluster.mergedTitle.trim()
        val description = cluster.mergedDescription.trim()
        val content = cluster.mergedContent.trim()
        if (title.isBlank() || description.isBlank() || content.isBlank()) {
            return rejectedRow(
                index,
                "rejected: mergedTitle, mergedDescription and mergedContent are required",
                title = title
            )
        }
        if (description.length > MemoryContentLimits.DESCRIPTION_CAP_CHARS) {
            return rejectedRow(
                index,
                "rejected: mergedDescription exceeds ${MemoryContentLimits.DESCRIPTION_CAP_CHARS} characters",
                title = title
            )
        }
        val mergedContent = applySectionRefs(content, cluster.sectionRefs)
        if (mergedContent.length > MemoryContentLimits.CONTENT_HARD_CAP_CHARS) {
            return rejectedRow(
                index,
                "rejected: mergedContent exceeds ${MemoryContentLimits.CONTENT_HARD_CAP_CHARS} characters; split the cluster",
                title = title
            )
        }

        val remaining = state.remainingArchives()
        if (unclaimed.size > remaining) {
            stats.skippedOverCap.incrementAndGet()
            return rejectedRow(
                index,
                "deferred: ${unclaimed.size} sources exceed the remaining budget of $remaining",
                title = title,
                sourceDocIds = unclaimed
            )
        }

        val sourceEntries = store.findByIds(unclaimed, state.scope, state.owner)
        // Sources of an earlier run are already archived; re-absorbing one would rewrite the history
        // pointer of a document this pass was never shown.
        val absorbed = sourceEntries.filter { !it.archived && it.id != null }
        val foundIds = absorbed.mapNotNull { it.id }
        if (foundIds.size < 2) {
            return rejectedRow(
                index,
                "rejected: fewer than 2 source documents were found (${foundIds.size}/${unclaimed.size})",
                title = title,
                sourceDocIds = unclaimed
            )
        }

        val name = mergedName(title, foundIds)
        val level = (absorbed.maxOfOrNull { it.level } ?: 0) + 1
        val maturity = absorbed.mapNotNull { it.maturity }.maxOrNull()
        val entry = MemoryEntry(
            name = name,
            description = description,
            type = state.type,
            content = mergedContent,
            path = "${state.type.dirName}/$name.md",
            // The merged body is a distillation, so the sources it compressed are the record: these tags
            // must survive or retrieval filters and the Memories page would make a merged entry harder
            // to find than the entries it absorbed. Union in source order, without inventing any new tag.
            keywords = absorbed.flatMap { it.keywords }.distinct(),
            scenarios = absorbed.flatMap { it.scenarios }.distinct(),
            created = absorbed.mapNotNull { it.created }.minOrNull() ?: LocalDate.now(),
            updated = LocalDate.now(),
            maturity = maturity,
            level = level,
            refs = foundIds
        )

        // Past the key-points target but inside the hard cap: stored, and counted, because a run that
        // keeps landing here means the clusters were too big to distill and grouping should shrink them.
        if (mergedContent.length > MemoryContentLimits.CONTENT_TARGET_CHARS) {
            stats.overTarget.incrementAndGet()
        }

        if (state.dryRun) {
            state.claim(foundIds)
            stats.plannedArchives.addAndGet(foundIds.size)
            return mapOf(
                "index" to index,
                "title" to title,
                "name" to name,
                "level" to level,
                "sourceDocIds" to foundIds,
                "dryRun" to true
            )
        }

        val written = store.writeEntry(entry, state.scope, state.owner)
        val mergedId = written.id
            ?: return rejectedRow(
                index,
                "rejected: EasyRAG returned no docId for '$name'",
                title = title,
                sourceDocIds = foundIds
            )
        state.claim(foundIds)
        stats.mergedWritten.incrementAndGet()

        val archived = store.archive(
            foundIds,
            MemoryArchiveUpdate(archived = true, mergedInto = mergedId),
            state.scope,
            state.owner
        )
        stats.archived.addAndGet(archived)
        // The merged document is already stored, so every source the archive call did not flag is a live
        // duplicate of it — searchable twice. EasyRAG answers per id, and the reason only ever reached
        // the log, so the count gap is the one thing a reader of the report can act on.
        if (archived < foundIds.size) {
            stats.archiveShortfall.addAndGet(foundIds.size - archived)
        }
        return mapOf(
            "index" to index,
            "title" to title,
            "name" to name,
            "level" to level,
            "mergedDocId" to mergedId,
            "sourceDocIds" to foundIds,
            "archived" to archived,
            "expectedArchives" to foundIds.size,
            "contentChars" to mergedContent.length
        )
    }

    /**
     * Shrink a proposed cluster to the merge pass's limits, dropping from the tail: the model lists a
     * cluster's sources in reading order, so the tail is the least likely to carry the main thread.
     */
    private fun trimToLimits(
        docIds: List<String>,
        byId: Map<String, MemoryIndexEntry>
    ): List<String> {
        val budget = settings.maxGroupSourceChars.coerceAtLeast(MemoryContentLimits.CONTENT_TARGET_CHARS)
        val maxSources = settings.maxSourcesPerGroup.coerceAtLeast(2)
        var result = docIds.take(maxSources)
        while (result.size >= 2 && sumChars(result, byId) > budget) {
            result = result.dropLast(1)
        }
        return if (result.size < 2) emptyList() else result
    }

    private fun sumChars(docIds: List<String>, byId: Map<String, MemoryIndexEntry>): Int =
        docIds.sumOf { byId[it]?.contentChars ?: 0 }

    private fun maxLevelOf(docIds: List<String>, byId: Map<String, MemoryIndexEntry>): Int =
        docIds.maxOfOrNull { byId[it]?.level ?: 0 } ?: 0

    /**
     * One previewed cluster's row. A real run's rows come from [commitCluster], which is the only thing
     * that knows a docId and an archived count; grouping can report the shape of what it proposed — and,
     * when a cluster never made the queue, why not.
     */
    private fun addGroupRow(
        index: Int,
        title: String,
        sourceDocIds: List<String>,
        sourceChars: Int,
        level: Int,
        state: MemoryConsolidationRunState,
        reason: String? = null
    ) {
        if (!state.dryRun) return
        val row = mutableMapOf<String, Any?>(
            "index" to index,
            "title" to title.trim(),
            "sourceDocIds" to sourceDocIds,
            "sourceChars" to sourceChars,
            "level" to level + 1,
            "dryRun" to true
        )
        if (reason != null) row["rejected"] = reason
        state.stats.clusters.add(row)
    }

    /** One cluster's verdict row: what the report shows for a cluster that did not commit. */
    private fun rejectedRow(
        index: Int,
        reason: String,
        title: String? = null,
        sourceDocIds: List<String> = emptyList()
    ): Map<String, Any?> = mapOf(
        "index" to index,
        "title" to title.orEmpty(),
        "sourceDocIds" to sourceDocIds,
        "rejected" to reason
    )

    /** Append `〔src:<docId>〕` markers at each anchor; unmatched refs are appended to the body. */
    private fun applySectionRefs(content: String, refs: List<ConsolidationSectionRef>): String {
        if (refs.isEmpty()) return content
        val unmatched = mutableListOf<String>()
        var result = content
        for (ref in refs) {
            val ids = ref.sourceDocIds.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            if (ids.isEmpty()) continue
            val markers = ids.joinToString("") { "〔src:$it〕" }
            val anchor = ref.anchor.trim()
            if (anchor.isNotEmpty() && result.contains(anchor)) {
                result = result.replaceFirst(anchor, anchor + markers)
            } else {
                unmatched.add(markers)
            }
        }
        return if (unmatched.isEmpty()) result else result + "\n\n" + unmatched.joinToString("\n")
    }

    /**
     * Deterministic merged name: the title slug plus a short hash of the source set, so re-running
     * the same cluster stays idempotent while different clusters with the same title do not collide.
     */
    private fun mergedName(title: String, sourceIds: List<String>): String {
        val slug = slugify(title).ifBlank { "merged" }
        val hash = sourceIds.sorted().joinToString(",").hashCode().toUInt().toString(16)
        return "${slug.take(MAX_NAME_LENGTH - hash.length - 2)}-$hash"
    }

    private fun slugify(title: String): String = title.lowercase()
        .map { c -> if (c.isLetterOrDigit() || c == '-' || c == '_' || c == '.') c else '-' }
        .joinToString("")
        .trim('-')
        .replace(Regex("-{2,}"), "-")
        .take(MAX_NAME_LENGTH)

    private companion object {
        const val MAX_NAME_LENGTH = 64
    }
}
