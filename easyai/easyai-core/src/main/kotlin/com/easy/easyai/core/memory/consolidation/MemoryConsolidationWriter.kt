package com.easy.easyai.core.memory.consolidation

import com.easy.easyai.core.memory.MemoryArchiveUpdate
import com.easy.easyai.core.memory.MemoryContentLimits
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryStore
import java.time.LocalDate

/**
 * Applies one consolidation batch's clusters to the memory store. This is the sole write path of a
 * consolidation run: the model only proposes clusters, and every decision to accept one is made here.
 *
 * The shape checks (a cluster needs 2+ sources, bodies inside the caps) came straight from the commit
 * tool this replaced, and the set checks are the ones a JSON Schema cannot express: a docId the model
 * invented, a source another cluster already absorbed, and a source archived by an earlier run all
 * have to be caught against the store. Nothing is deleted — absorbed sources are archived and the
 * merged document references them through `ref_ids`.
 */
internal class MemoryConsolidationWriter(private val store: MemoryStore) {

    /**
     * @param uncoveredDocIds docIds of the batch that appeared in neither a cluster nor the leftover set
     *
     * Deliberately the only number here: per-batch commit counts duplicate what the run's own counters
     * already sum, and reporting a previewed cluster as "rejected" alongside genuinely rejected ones
     * made the same field mean two incompatible things.
     */
    data class BatchOutcome(val uncoveredDocIds: List<String>)

    /**
     * Write (or, for a dry run, only plan) every cluster of one batch and fold the totals into
     * [state]. A cluster that fails a check is reported and skipped; it never aborts the batch.
     *
     * [BatchOutcome.uncoveredDocIds] is returned rather than counted here: the caller may still be able
     * to recover those sources with one more question, and only it knows whether it did.
     */
    suspend fun writeBatch(
        batchDocIds: List<String>,
        output: MemoryConsolidationOutput,
        state: MemoryConsolidationRunState
    ): BatchOutcome {
        val claimedByModel = mutableSetOf<String>()

        for ((index, cluster) in output.clusters.withIndex()) {
            state.stats.clusters.add(commitCluster(index, cluster, state))
            cluster.sourceDocIds.map { it.trim() }.filter { it.isNotEmpty() }.let { claimedByModel += it }
        }

        val leftovers = output.leftoverDocIds.map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val uncovered = batchDocIds.filter { it.isNotEmpty() && it !in claimedByModel && it !in leftovers }
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
            // The merged body claims to keep every distinct fact, and retrieval filters and the Memories
            // page read these tags: dropping them would make a merged entry harder to find than the
            // sources it absorbed. Union in source order, without inventing any new tag.
            keywords = absorbed.flatMap { it.keywords }.distinct(),
            scenarios = absorbed.flatMap { it.scenarios }.distinct(),
            created = absorbed.mapNotNull { it.created }.minOrNull() ?: LocalDate.now(),
            updated = LocalDate.now(),
            maturity = maturity,
            level = level,
            refs = foundIds
        )

        if (state.dryRun) {
            // A preview consumes the same budget as the write it describes, otherwise it would
            // promise every cluster it was shown while the real run defers the ones over the cap.
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
            "expectedArchives" to foundIds.size
        )
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
