package com.easy.easyai.core.task

import com.easy.easyai.core.memory.ConsolidationReport
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryIndexEntry
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.memory.consolidation.ConsolidationOutcome
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSettingsStore
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSliceState
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSessionTitlePort
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
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
 * After a run it also records what the scheduler needs to know about that slice — the active docId
 * fingerprint **as of now**, how the run ended, and how long to leave the slice alone — because a count
 * threshold alone cannot tell "the store grew" from "we just merged it down and it will grow again".
 * That bookkeeping is the difference between one pass a day per over-threshold slice and the same
 * question asked forever. A run that *failed* is recorded too, and on a shorter delay: it left the
 * fingerprint at its previous value, which the next scan reads as "changed", so without a delay a slice
 * that consistently outlives its wall-clock budget would re-burn that whole budget at every scan.
 */
class MemoryConsolidationTaskHandler(
    private val agent: MemoryConsolidationAgent,
    private val taskStore: AsyncTaskStore? = null,
    private val titlePort: MemoryConsolidationSessionTitlePort? = null,
    private val memoryStore: MemoryStore? = null,
    private val settingsStore: MemoryConsolidationSettingsStore? = null,
    private val backoffBaseDays: Int = DEFAULT_BACKOFF_BASE_DAYS,
    private val backoffMaxDays: Int = DEFAULT_BACKOFF_MAX_DAYS,
    private val failedRunBackoffBaseDays: Int = DEFAULT_FAILED_RUN_BACKOFF_BASE_DAYS,
    private val memoryCodes: Set<String>
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
        if (typeDirName !in memoryCodes) {
            return TaskOutcome.Failure(
                "Memory type '$typeDirName' is not part of the active domain catalog " +
                    "(available: ${memoryCodes.joinToString()}); the task was queued under a different " +
                    "catalog and cannot be re-run"
            )
        }
        val memoryType = MemoryType(typeDirName)
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
        } catch (e: CancellationException) {
            // Must stay ahead of the catch below: CancellationException is an IllegalStateException, and
            // so is the timeout a long run reports. Booking a user's cancel or a shutdown as a failed run
            // would push the slice into a delay nobody asked for, and the runner reads a bare
            // cancellation as exactly that — parking the row INTERRUPTED on shutdown, CANCELLED otherwise.
            // A timeout is different: the slice really did outlive its budget, so it owes the same shorter
            // delay a failed run gets or the next scan re-burns the whole budget.
            if (e is TimeoutCancellationException && !dryRun) {
                recordFailedRun(userId, scope, projectPath, memoryType, owner)
            }
            throw e
        } catch (e: Exception) {
            if (!dryRun) {
                recordFailedRun(userId, scope, projectPath, memoryType, owner)
            }
            throw e
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
     * Record that this slice's run could not do its job, on the short delay that cause deserves.
     *
     * Runs under [NonCancellable] because the delay is the whole point: a shutdown racing a timeout is
     * exactly when this is most likely to be skipped, and skipping it is what leaves the slice due again
     * at the next scan. [AsyncTaskRunner] lands its own terminal write the same way.
     */
    private suspend fun recordFailedRun(
        userId: String,
        scope: MemoryScope,
        projectPath: String?,
        memoryType: MemoryType,
        owner: MemoryOwnerContext
    ) {
        withContext(NonCancellable) {
            recordSliceOutcome(userId, scope, projectPath, memoryType, owner, ConsolidationOutcome.FAILED)
        }
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
     * that succeeded, and on the failure path it must never replace the error being rethrown.
     */
    private suspend fun recordSliceState(
        userId: String,
        scope: MemoryScope,
        projectPath: String?,
        memoryType: MemoryType,
        owner: MemoryOwnerContext,
        report: ConsolidationReport
    ) {
        recordSliceOutcome(userId, scope, projectPath, memoryType, owner, outcomeOf(report))
    }

    /**
     * What a finished run says about the slice, read off numbers the writer already counted.
     *
     * Only a written merge is productive. A run whose prompts all died in transport, or all came back
     * unusable, also archived nothing — but that is not a verdict that the slice is coherent, and
     * treating it as one is what pushed a slice out by a week over a single bad night.
     */
    private fun outcomeOf(report: ConsolidationReport): ConsolidationOutcome = when {
        report.mergedWritten > 0 -> ConsolidationOutcome.PRODUCTIVE
        report.llmFailures > 0 || report.rejectedBatches > 0 -> ConsolidationOutcome.FAILED
        else -> ConsolidationOutcome.NOTHING_TO_MERGE
    }

    /**
     * Fold one outcome into the slice's scheduler memory.
     *
     * The streak counts consecutive unproductive runs regardless of cause: it measures how persistently
     * this slice has refused to yield. The cause picks the base the streak is applied to, because the two
     * deserve very different waits — [ConsolidationOutcome.NOTHING_TO_MERGE] is the model's own verdict
     * that re-asking soon would re-earn the same answer, while [ConsolidationOutcome.FAILED] says nothing
     * about the slice at all and should be retried once the transport has had a day to recover.
     *
     * The cause also decides whether the fingerprint baseline moves, and this is the part that is easy to
     * get wrong. A verdict — productive or empty — disposes of the material, so the baseline advances to
     * what is active now and the fingerprint gate can stop the same question being asked again. A failed
     * run disposes of *nothing*: the material is still outstanding, so the baseline stays where the last
     * verdict left it. Re-measuring it here would make the fingerprint match on the next scan, and then
     * the gate rather than [nextEligibleAt] would decide the retry — the slice would sit until new
     * memories happened to arrive instead of being retried once the transport recovered. It also skips a
     * listing on the path where the store may be the thing that is broken.
     */
    private suspend fun recordSliceOutcome(
        userId: String,
        scope: MemoryScope,
        projectPath: String?,
        memoryType: MemoryType,
        owner: MemoryOwnerContext,
        outcome: ConsolidationOutcome
    ) {
        val store = memoryStore ?: return
        val settings = settingsStore ?: return
        val sliceKey = sliceKey(scope, projectPath, memoryType)
        runCatching {
            val previous = settings.get(userId)?.sliceStates?.get(sliceKey)
            val fingerprint = if (outcome == ConsolidationOutcome.FAILED) {
                previous?.activeFingerprint
            } else {
                MemoryIndexEntry.fingerprint(
                    store.listActiveIndex(scope, owner, memoryType).map { it.docId }
                )
            }
            val now = System.currentTimeMillis()
            val streak = if (outcome == ConsolidationOutcome.PRODUCTIVE) 0
            else (previous?.zeroGainStreak ?: 0) + 1
            val baseDays = when (outcome) {
                ConsolidationOutcome.PRODUCTIVE -> 0
                ConsolidationOutcome.FAILED -> failedRunBackoffBaseDays
                ConsolidationOutcome.NOTHING_TO_MERGE -> backoffBaseDays
            }
            val delayDays = if (streak == 0 || baseDays == 0) 0 else minOf(
                baseDays.toLong() shl (streak - 1).coerceAtMost(MAX_BACKOFF_SHIFT),
                backoffMaxDays.toLong()
            )
            settings.recordSliceState(
                userId,
                sliceKey,
                MemoryConsolidationSliceState(
                    activeFingerprint = fingerprint,
                    lastRunAt = now,
                    zeroGainStreak = streak,
                    nextEligibleAt = if (delayDays > 0) now + delayDays * MILLIS_PER_DAY else null,
                    lastOutcome = outcome
                )
            )
            logger.debug(
                "Consolidation baseline recorded: slice={}, outcome={}, fingerprint={}, streak={}, delayedDays={}",
                sliceKey, outcome, fingerprint, streak, delayDays
            )
        }.onFailure {
            logger.warn("Failed to record consolidation baseline for slice {}: {}", sliceKey, it.message)
        }
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

        /** Doubled per consecutive unproductive run, so a hard cap is what bounds it, not the streak. */
        private const val MAX_BACKOFF_SHIFT = 5
        private const val MILLIS_PER_DAY = 24L * 60 * 60 * 1000
        private const val DEFAULT_BACKOFF_BASE_DAYS = 7
        private const val DEFAULT_BACKOFF_MAX_DAYS = 30

        /** A broken run is not a verdict about the slice, so it is retried far sooner than an empty one. */
        private const val DEFAULT_FAILED_RUN_BACKOFF_BASE_DAYS = 1

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
