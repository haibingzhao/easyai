package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
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
 * each measured against that type's threshold from the user's `params`. Measurement is a count
 * ([MemoryStore.countActiveByType]) rather than a listing, because this runs on every tick for every
 * opted-in user and only the number matters. Nothing is proposed for a slice that still has a
 * non-terminal task, so a tick landing while an earlier pass is queued or running does not
 * double-process the same entries.
 *
 * Cron, tick timing and submission belong to the runner; this class deliberately holds no clock and
 * never submits, which is what keeps a second schedulable type from needing its own scheduler.
 */
class MemoryConsolidationTaskPlanner(
    private val settings: MemoryConsolidationSettings,
    private val memoryStore: MemoryStore?,
    private val projectStore: AsyncProjectStore?
) : ScheduledTaskPlanner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION

    override val defaultCron: String? get() = settings.dailyCron

    override val perUserPerTickLimit: Int get() = settings.maxTasksPerTickPerUser

    override suspend fun plan(context: ScheduledTaskPlanContext): List<TaskDraft> {
        if (!settings.available) return emptyList()
        val store = memoryStore ?: return emptyList()
        val user = MemoryConsolidationUserSettings.fromScheduled(context.setting)
        val pending = pendingKeys(context.pendingTasks)
        val memoryTypes = MemoryType.entriesFor(DomainCatalog.activeDomain)
        val drafts = mutableListOf<TaskDraft>()
        for (slice in slicesOf(context.userId)) {
            // A slice with every type already queued needs no measurement at all, so skip its read.
            if (memoryTypes.all { pending.contains(keyOf(slice, it)) }) continue
            // One count query per slice: the queued cap is the runner's single-count job, and a
            // counting read is cheap enough that pre-truncating here would only duplicate it.
            val activeByType = store.countActiveByType(slice.scope, slice.owner)
            for (memoryType in memoryTypes) {
                if (pending.contains(keyOf(slice, memoryType))) {
                    logger.debug(
                        "Memory consolidation already pending for user={} scope={} project={} type={}",
                        context.userId, slice.scope, slice.projectPath, memoryType.dirName
                    )
                    continue
                }
                if ((activeByType[memoryType] ?: 0) < thresholdFor(user, memoryType)) continue
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
        runCatching { projects.findAll(limit = null, userId = userId, includeTemp = false).toList() }
            .onFailure { logger.warn("Failed to list projects for user {}: {}", userId, it.message) }
            .getOrDefault(emptyList())
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

    /** Duplicate suppression is per slice, not per task: one slice is the unit of consolidation work. */
    private fun keyOf(slice: Slice, memoryType: MemoryType): String =
        MemoryConsolidationTaskHandler.sliceKey(slice.scope, slice.projectPath, memoryType)

    /** Keys of the slices this user already has unsettled work for, resolved once per plan. */
    private fun pendingKeys(tasks: List<AsyncTask>): Set<String> =
        tasks.mapNotNull { task ->
            if (task.status.isTerminal) null else MemoryConsolidationTaskHandler.sliceKeyOf(task)
        }.toSet()
}
