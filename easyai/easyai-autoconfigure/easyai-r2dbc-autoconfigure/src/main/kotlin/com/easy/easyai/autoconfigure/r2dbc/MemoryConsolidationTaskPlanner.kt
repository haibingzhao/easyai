package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryIndexEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSliceState
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationUserSettings
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.MemoryConsolidationTaskHandler
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.TaskDraft
import com.easy.easyai.repository.project.AsyncProjectStore
import kotlinx.coroutines.flow.toList
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.util.UUID

/**
 * Answers "which consolidation work is due for this user right now" for [ScheduledTaskRunner].
 *
 * A slice is one (scope, project, memory type): the user's GLOBAL memory plus one per non-temp project,
 * each measured against that type's threshold from the user's `params`. Measurement is one cheap listing
 * per slice ([MemoryStore.listActiveIndex]) that answers three questions at once — how many of each
 * type's entries are still mergeable material, which docIds make up the slice, and therefore whether the
 * slice changed at all since the last pass. Nothing is read per entry body, because this runs on every
 * tick for every opted-in user.
 *
 * Three gates decide "due", and the last two exist because a count threshold on its own does not:
 *
 * 1. no unsettled task for the slice (a tick landing while an earlier pass is queued or running must not
 *    double-process the same entries);
 * 2. [MemoryConsolidationSliceState.nextEligibleAt] not in force — a run that archived nothing left the
 *    count untouched, so without a delay the scan would re-ask the same question every day forever;
 * 3. the active docId fingerprint differs from the one recorded when the last run **finished** — equal
 *    means nothing arrived and nothing went away, so the same clustering answer would be recomputed.
 *    Recomputing the baseline after each run is what stops a run's own archiving and merged writes from
 *    re-dirtying the slice, which is the failure mode that had yesterday's digest being distilled into
 *    level-2, then level-3, on a slice that gained two memories.
 *
 * Cron, tick timing and submission belong to the runner; this class deliberately holds no clock and
 * never submits, which is what keeps a second schedulable type from needing its own scheduler.
 */
class MemoryConsolidationTaskPlanner(
    private val settings: MemoryConsolidationSettings,
    private val memoryStore: MemoryStore?,
    private val projectStore: AsyncProjectStore?,
    private val domainCatalog: DomainCatalog
) : ScheduledTaskPlanner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION

    override val defaultCron: String? get() = settings.dailyCron

    override val perUserPerTickLimit: Int get() = settings.maxTasksPerTickPerUser

    override suspend fun plan(context: ScheduledTaskPlanContext): List<TaskDraft> {
        if (!settings.available) return emptyList()
        val store = checkNotNull(memoryStore) { "Memory store is unavailable for scheduled consolidation" }
        val user = MemoryConsolidationUserSettings.fromScheduled(context.setting)
        val pending = pendingKeys(context.pendingTasks)
        val memoryTypes = domainCatalog.active().map { MemoryType(it.code) }
        val nowEpochMs = context.now.toEpochMilli()
        val drafts = mutableListOf<TaskDraft>()
        for (slice in slicesOf(context.userId)) {
            // Gates that need no store read are settled first, so a fully deferred or fully pending slice
            // never costs a listing.
            val candidates = memoryTypes.mapNotNull { memoryType ->
                val key = MemoryConsolidationTaskHandler.sliceKey(slice.scope, slice.projectPath, memoryType)
                if (pending.contains(key)) return@mapNotNull null
                val state = user.sliceStates[key]
                if (state != null && state.nextEligibleAt != null && nowEpochMs < state.nextEligibleAt!!) {
                    logger.debug(
                        "Memory consolidation deferred for user={} slice={} until {}",
                        context.userId, key, state.nextEligibleAt
                    )
                    return@mapNotNull null
                }
                Triple(key, memoryType, state)
            }
            if (candidates.isEmpty()) continue
            // One listing per (scope, project) answers every remaining type: counts, and the docId set each
            // type's fingerprint is computed from. A memory added to one type must not re-dirty another.
            val index = store.listActiveIndex(slice.scope, slice.owner)
            if (index.isEmpty()) continue
            for ((key, memoryType, state) in candidates) {
                val typeIndex = index.filter { it.type == memoryType }
                // The threshold counts only what a run would actually be offered. An entry at the depth
                // ceiling is a finished digest, never material, so a slice made of little else would pass
                // a raw count, build a task and a hidden session, and have the agent return an empty
                // report at once — a no-op the scheduler would then bill to its zero-gain delay.
                val eligibleCount = typeIndex.count { it.level < settings.maxLevel }
                if (eligibleCount < thresholdFor(user, memoryType)) continue
                // Deliberately over *every* active level, not just the eligible ones. The run records its
                // baseline from a listing it does not filter by level, so the two must describe the same
                // set: narrowing this one would make the comparison never match, and the slice would be
                // consolidated again at every scan. The threshold asks "is there enough material"; this
                // asks "did anything change", and those are different sets.
                val fingerprint = MemoryIndexEntry.fingerprint(typeIndex.map { it.docId })
                if (state?.activeFingerprint == fingerprint) {
                    logger.debug(
                        "Memory consolidation already current for user={} slice={}: no entry arrived or " +
                            "left since the last pass",
                        context.userId, key
                    )
                    continue
                }
                drafts += draft(context.userId, user.owners, slice, memoryType)
            }
        }
        return drafts
    }

    private fun thresholdFor(user: MemoryConsolidationUserSettings, memoryType: MemoryType): Int =
        user.typeThresholds[memoryType.dirName] ?: settings.defaultThreshold

    private suspend fun slicesOf(userId: String): List<Slice> {
        val slices = mutableListOf(Slice(MemoryScope.GLOBAL, MemoryOwnerContext(userId, null), null))
        val projects = projectStore ?: return slices
        projects.findAll(limit = null, userId = userId, includeTemp = false).toList()
            .forEach { project ->
                val path = project.path
                slices += Slice(MemoryScope.PROJECT, MemoryOwnerContext(userId, Paths.get(path)), path)
            }
        return slices
    }

    /**
     * The hidden session is minted here so the task row and its payload agree on it from the first
     * write: the retry path reuses that session and keeps every attempt's transcript in one place.
     *
     * The payload carries the saved owner set, not just the user: a tick has no security context to
     * re-derive the group bucket from, and the MEMORY_CURATION gate at the web boundary reads
     * self + group, so a group-level choice would open the switch and then fail every scheduled run.
     */
    private fun draft(userId: String, owners: List<String>, slice: Slice, memoryType: MemoryType): TaskDraft {
        val sessionId = UUID.randomUUID().toString()
        return TaskDraft(
            userId = userId,
            type = AsyncTaskType.MEMORY_CONSOLIDATION,
            title = "",
            sessionId = sessionId,
            payload = mapOf(
                MemoryConsolidationTaskHandler.KEY_SCOPE to slice.scope.name.lowercase(),
                MemoryConsolidationTaskHandler.KEY_USER_ID to userId,
                MemoryConsolidationTaskHandler.KEY_PROJECT_PATH to slice.projectPath,
                MemoryConsolidationTaskHandler.KEY_OWNERS to owners,
                MemoryConsolidationTaskHandler.KEY_TYPE to memoryType.dirName,
                MemoryConsolidationTaskHandler.KEY_SESSION_ID to sessionId,
                MemoryConsolidationTaskHandler.KEY_DRY_RUN to false
            )
        )
    }

    private data class Slice(
        val scope: MemoryScope,
        val owner: MemoryOwnerContext,
        val projectPath: String?
    )

    /** Keys of the slices this user already has unsettled work for, resolved once per plan. */
    private fun pendingKeys(tasks: List<AsyncTask>): Set<String> =
        tasks.mapNotNull { task ->
            if (task.status.isTerminal) null else MemoryConsolidationTaskHandler.sliceKeyOf(task)
        }.toSet()
}
