package com.easy.easyai.core.task

import com.easy.easyai.core.memory.ConsolidationReport
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryIndexEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSettingsStore
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSliceState
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSessionTitlePort
import org.slf4j.LoggerFactory
import java.nio.file.Paths
import java.time.LocalDate
import java.util.UUID

/**
 * [TaskHandler] bridging the async-task subsystem to [MemoryConsolidationAgent].
 *
 * The target slice and type are restored entirely from [AsyncTask.payload], so a task submitted
 * before a restart can be re-run. Runs are backed by a hidden session (id stored on the task row and
 * payload): the retry path reuses it, which keeps the full transcript of every attempt in one place.
 *
 * After a real run it also records what the scheduler needs to know about that slice — the active docId
 * fingerprint **as of now**, and whether this pass produced anything — because a count threshold alone
 * cannot tell "the store grew" from "we just merged it down and it will grow again". That bookkeeping is
 * the difference between one pass a day per over-threshold slice and the same question asked forever.
 */
class MemoryConsolidationTaskHandler(
    private val agent: MemoryConsolidationAgent,
    private val taskStore: AsyncTaskStore? = null,
    private val titlePort: MemoryConsolidationSessionTitlePort? = null,
    private val memoryStore: MemoryStore? = null,
    private val settingsStore: MemoryConsolidationSettingsStore? = null,
    private val backoffBaseDays: Int = DEFAULT_BACKOFF_BASE_DAYS,
    private val backoffMaxDays: Int = DEFAULT_BACKOFF_MAX_DAYS
) : TaskHandler {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: AsyncTaskType = AsyncTaskType.MEMORY_CONSOLIDATION

    override suspend fun execute(task: AsyncTask): TaskOutcome {
        val payload = task.payload
        val scope = (payload[KEY_SCOPE] as? String)
            ?.let { runCatching { MemoryScope.valueOf(it.uppercase()) }.getOrNull() }
            ?: return TaskOutcome.Failure("Invalid or missing scope in task payload")
        val userId = payload[KEY_USER_ID] as? String ?: task.userId
        val projectPath = (payload[KEY_PROJECT_PATH] as? String)?.takeIf { it.isNotBlank() }
        val owner = MemoryOwnerContext(userId = userId, projectPath = projectPath?.let { Paths.get(it) })
        val owners = (payload[KEY_OWNERS] as? List<*>)?.filterIsInstance<String>().orEmpty()
            .ifEmpty { listOf(userId) }
        val typeDirName = payload[KEY_TYPE] as? String
            ?: return TaskOutcome.Failure("Invalid or missing memory type in task payload")
        val memoryType = MemoryType.fromDirName(typeDirName)
            ?: return TaskOutcome.Failure("Unknown memory type '$typeDirName'")
        val dryRun = payload[KEY_DRY_RUN] as? Boolean ?: false

        val sessionId = (payload[KEY_SESSION_ID] as? String)?.takeIf { it.isNotBlank() }
            ?: task.sessionId
            ?: UUID.randomUUID().toString()
        if (task.sessionId.isNullOrBlank()) {
            taskStore?.updateSessionId(task.id, sessionId)
        }

        logger.debug(
            "Running memory consolidation task {}: scope={}, type={}, dryRun={}, session={}",
            task.id, scope, memoryType.dirName, dryRun, sessionId
        )

        val report = try {
            agent.consolidate(
                scope = scope,
                owner = owner,
                type = memoryType,
                owners = owners,
                sessionId = sessionId,
                dryRun = dryRun
            )
        } finally {
            // The session row only exists after the agent created it; renaming earlier would no-op.
            if (task.retryCount == 0) {
                titlePort?.rename(sessionId, sessionTitle(memoryType), userId)
            }
        }
        if (!dryRun) {
            recordSliceState(userId, scope, projectPath, memoryType, owner, report)
        }
        return TaskOutcome.Success(report.toResult())
    }

    /**
     * Store this slice's new baseline: what is active now, and how long to leave it alone.
     *
     * The fingerprint is taken *after* the run on purpose. Measured before it, the pass's own archiving
     * and merged writes would look like fresh work and the slice would be due again on the next tick —
     * which is how a digest gets distilled into a digest of a digest.
     *
     * Failures here are logged and dropped: the memories are already consolidated, and losing the
     * bookkeeping only means the next scan may ask one unnecessary question. It must never fail a run
     * that succeeded.
     */
    private suspend fun recordSliceState(
        userId: String,
        scope: MemoryScope,
        projectPath: String?,
        memoryType: MemoryType,
        owner: MemoryOwnerContext,
        report: ConsolidationReport
    ) {
        val store = memoryStore ?: return
        val settings = settingsStore ?: return
        val sliceKey = sliceKey(scope, projectPath, memoryType)
        val previous = runCatching { settings.get(userId)?.sliceStates?.get(sliceKey) }.getOrNull()
        val fingerprint = runCatching {
            MemoryIndexEntry.fingerprint(
                store.listActiveIndex(scope, owner, memoryType).map { it.docId }
            )
        }.getOrElse {
            logger.warn("Failed to fingerprint consolidation slice {}: {}", sliceKey, it.message)
            return
        }
        val now = System.currentTimeMillis()
        // Only a pass that wrote nothing counts as no-gain: an archived source stops the same question
        // from being worth asking again, while a fully-refused batch leaves the store untouched.
        val gained = report.mergedWritten > 0
        val streak = if (gained) 0 else (previous?.zeroGainStreak ?: 0) + 1
        val delayDays = if (streak == 0) 0 else minOf(
            backoffBaseDays.toLong() shl (streak - 1).coerceAtMost(MAX_BACKOFF_SHIFT),
            backoffMaxDays.toLong()
        )
        settings.recordSliceState(
            userId,
            sliceKey,
            MemoryConsolidationSliceState(
                activeFingerprint = fingerprint,
                lastRunAt = now,
                zeroGainStreak = streak,
                nextEligibleAt = if (delayDays > 0) now + delayDays * MILLIS_PER_DAY else null
            )
        )
        logger.debug(
            "Consolidation baseline recorded: slice={}, fingerprint={}, zeroGainStreak={}, delayedDays={}",
            sliceKey, fingerprint, streak, delayDays
        )
    }

    private fun sessionTitle(type: MemoryType): String =
        "Memory consolidation · ${type.dirName} · ${LocalDate.now()}"

    /** Structured numbers only — display text is rendered per locale by the UI, never persisted. */
    private fun ConsolidationReport.toResult(): Map<String, Any?> = mapOf(
        "type" to type.dirName,
        "scope" to scope.name,
        "activeBefore" to activeBefore,
        "clustersProposed" to clustersProposed,
        "mergedWritten" to mergedWritten,
        "archived" to archived,
        "archiveShortfall" to archiveShortfall,
        "skippedSingletons" to skippedSingletons,
        "skippedOverCap" to skippedOverCap,
        "droppedGroups" to droppedGroups,
        "overTarget" to overTarget,
        "rejectedBatches" to rejectedBatches,
        "llmFailures" to llmFailures,
        "llmFailureReason" to llmFailureReason,
        "uncoveredDocIds" to uncoveredDocIds,
        "dryRun" to dryRun,
        "sessionId" to sessionId,
        "plan" to plan
    )

    companion object {
        const val KEY_SCOPE = "scope"
        const val KEY_USER_ID = "userId"
        const val KEY_PROJECT_PATH = "projectPath"
        const val KEY_OWNERS = "owners"
        const val KEY_TYPE = "type"
        const val KEY_SESSION_ID = "sessionId"
        const val KEY_DRY_RUN = "dryRun"

        /** Doubled per consecutive zero-gain run, so a hard cap is what bounds it, not the streak. */
        private const val MAX_BACKOFF_SHIFT = 5
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        private const val DEFAULT_BACKOFF_BASE_DAYS = 7
        private const val DEFAULT_BACKOFF_MAX_DAYS = 30

        /**
         * Identity of the consolidation work unit: one `(scope, project, memory type)` slice.
         *
         * Duplicate suppression, the fingerprint baseline and the zero-gain delay are all per slice,
         * never per task — two runs over the same slice read the same active set, each write their own
         * merged entries, and fight over the same sources to archive, which is exactly the duplicate the
         * consolidation exists to remove. Both the scheduled planner and the manual web entry point key on
         * this so they agree.
         */
        @JvmStatic
        fun sliceKey(scope: MemoryScope, projectPath: String?, type: MemoryType): String =
            "${scope.name.lowercase()}|${projectPath ?: ""}|${type.dirName}"

        /** The [sliceKey] of an already-built task, or null when its payload does not name one slice. */
        @JvmStatic
        fun sliceKeyOf(task: AsyncTask): String? {
            val scope = task.payload[KEY_SCOPE] as? String ?: return null
            val type = task.payload[KEY_TYPE] as? String ?: return null
            val projectPath = (task.payload[KEY_PROJECT_PATH] as? String).orEmpty()
            return "$scope|$projectPath|$type"
        }
    }
}
