package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskDraft
import com.easy.easyai.core.task.TaskTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Drives every schedulable [AsyncTaskType] off the `scheduled_task_setting` rows: each tick reads the
 * opted-in users, resolves that row's cron, and hands due rows to the type's [ScheduledTaskPlanner],
 * then submits whatever the planner proposed.
 *
 * All scheduling mechanics live here — tick timing, cron resolution, the pending-task input planners
 * need for duplicate suppression, and the per-user batch cap — so a new schedulable type contributes
 * only a planner that decides which work is due. Planners never touch a clock or a cron expression.
 *
 * A row whose type has no registered planner is skipped: preferences outlive the code that reads them
 * (a feature switched off, a rolled-back build), and one unusable row must not stall the scan.
 *
 * The single-instance assumption that governs [AsyncTaskRunner.reconcileOrphans] applies here too:
 * concurrent schedulers sharing one database would both enqueue the same due work.
 */
class ScheduledTaskRunner(
    private val settingStore: ScheduledTaskSettingStore?,
    private val taskStore: AsyncTaskStore?,
    private val taskRunner: AsyncTaskRunner?,
    planners: List<ScheduledTaskPlanner>,
    private val clock: () -> LocalDateTime = { LocalDateTime.now() }
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val plannersByType: Map<AsyncTaskType, ScheduledTaskPlanner> =
        planners.associateBy { it.type }.also { byType ->
            // One planner per type is the whole registration contract, so a collision is a wiring
            // mistake, not a routing choice: say so instead of silently keeping the last bean.
            planners.groupBy { it.type }
                .filterValues { it.size > 1 }
                .forEach { (type, duplicates) ->
                    logger.warn(
                        "{} planners registered for type {} ({}); '{}' serves it, the rest are dead beans",
                        duplicates.size, type.key,
                        duplicates.joinToString(", ") { it.javaClass.simpleName },
                        byType[type]?.javaClass?.simpleName
                    )
                }
        }

    /** Re-entrancy guard: a slow scan must not overlap the next tick. */
    private val scanning = AtomicBoolean(false)

    /** Instant the previous scan started, which is where this scan's due-window begins. */
    private val lastScanAt = AtomicReference<LocalDateTime?>(null)

    init {
        logger.info(
            "Scheduled task runner wired for {} type(s): {}",
            plannersByType.size,
            plannersByType.keys.map { it.key }
        )
    }

    @Scheduled(cron = "\${easyai.tasks.scheduled.scan-cron:0 * * * * *}")
    fun tick() {
        scope.launch {
            if (!scanning.compareAndSet(false, true)) {
                logger.debug("Scheduled task scan still running; skipping this tick")
                return@launch
            }
            try {
                scanOnce()
            } catch (e: CancellationException) {
                // Preserve structured-concurrency cancellation (e.g. destroy()); never log it as a failure.
                throw e
            } catch (e: Exception) {
                logger.error("Scheduled task scan failed: {}", e.message, e)
            } finally {
                scanning.set(false)
            }
        }
    }

    /** Visible for tests: one full pass at [now]. */
    suspend fun scanOnce(now: LocalDateTime = clock()) {
        // Each scan owns the window since the previous one: a longer tick period widens the window
        // instead of silently dropping the fires inside it. Advancing before the pass means a scan
        // that fails mid-loop loses that window rather than firing the same work twice.
        val windowStart = lastScanAt.getAndSet(now) ?: now.minusSeconds(FIRST_SCAN_LOOKBACK_SECONDS)
        val store = settingStore ?: return
        val settings = runCatching { store.listEnabled() }
            .onFailure { logger.warn("Failed to list scheduled task settings: {}", it.message) }
            .getOrDefault(emptyList())
        for (setting in settings) {
            val planner = plannersByType[setting.taskType]
            if (planner == null) {
                logger.debug(
                    "No planner registered for scheduled type {}; skipping user {}",
                    setting.taskType.key, setting.userId
                )
                continue
            }
            if (!isDue(setting, planner, windowStart, now)) continue
            submitFor(setting, planner, now)
        }
    }

    override fun destroy() {
        scope.cancel()
    }

    /**
     * Plan and submit one user's due work. A planner that throws costs only its own user this tick, so
     * one bad slice query cannot drop the whole scan.
     */
    private suspend fun submitFor(setting: ScheduledTaskSetting, planner: ScheduledTaskPlanner, now: LocalDateTime) {
        val context = ScheduledTaskPlanContext(
            userId = setting.userId,
            setting = setting,
            now = now,
            pendingTasks = nonTerminalTasks(setting.userId, planner.type)
        )
        val drafts = runCatching { planner.plan(context) }.getOrElse {
            logger.error(
                "Scheduled plan failed for user {} type {}: {}",
                setting.userId, planner.type.key, it.message, it
            )
            return
        }
        if (drafts.isEmpty()) return
        val runner = taskRunner
        if (runner == null) {
            logger.warn(
                "Scheduled drafts dropped, no task runner available: user={} type={} drafts={}",
                setting.userId, planner.type.key, drafts.size
            )
            return
        }
        val accepted = drafts.take(planner.perUserPerTickLimit)
        if (accepted.size < drafts.size) {
            logger.warn(
                "Capped scheduled drafts for user {} type {} at {} (planner proposed {})",
                setting.userId, planner.type.key, accepted.size, drafts.size
            )
        }
        for (draft in accepted) {
            val task = toTask(draft)
            val submitted = runCatching { runner.submit(task) }
                .onFailure { logger.error("Failed to submit scheduled task: {}", it.message, it) }
                .getOrNull()
            if (submitted == null) {
                // submit() returns null both when no store is wired and when the insert failed.
                logger.warn(
                    "Scheduled task not submitted (no store, or persist failed): user={} type={}",
                    draft.userId, draft.type.key
                )
                continue
            }
            logger.info(
                "Scheduled task created: user={}, type={}, task={}, session={}",
                draft.userId, draft.type.key, submitted.id, submitted.sessionId
            )
        }
    }

    /** The driver owns identity and provenance: a planner only proposes what should run. */
    private fun toTask(draft: TaskDraft): AsyncTask = AsyncTask(
        id = UUID.randomUUID().toString(),
        userId = draft.userId,
        type = draft.type,
        trigger = TaskTrigger.SCHEDULE,
        title = draft.title,
        sessionId = draft.sessionId,
        payload = draft.payload
    )

    /**
     * Due when the row's cron fired somewhere in `(windowStart, now]`. `CronExpression.next` is
     * exclusive of its reference, so consecutive windows tile the timeline: one fire makes exactly
     * one scan due.
     */
    private fun isDue(
        setting: ScheduledTaskSetting,
        planner: ScheduledTaskPlanner,
        windowStart: LocalDateTime,
        now: LocalDateTime
    ): Boolean {
        val userCron = setting.cron?.takeIf { it.isNotBlank() }
        val expression = userCron ?: planner.defaultCron ?: return false
        val cron = parseCron(expression) ?: unusableCron(expression, userCron, setting, planner) ?: return false
        val next = cron.next(windowStart) ?: return false
        return !next.isAfter(now)
    }

    /**
     * Resolves what to do when [isDue] could not parse the cron in play. A bad per-user override falls
     * back to the planner default; a bad default leaves nothing to fall back to, and the row is not due.
     */
    private fun unusableCron(
        expression: String,
        userCron: String?,
        setting: ScheduledTaskSetting,
        planner: ScheduledTaskPlanner
    ): CronExpression? {
        if (userCron == null) {
            logger.warn("Invalid deployment scan cron '{}' for task type {}", expression, planner.type.key)
            return null
        }
        val default = planner.defaultCron?.let { parseCron(it) }
        if (default == null) {
            logger.warn(
                "Invalid scan cron '{}' for user {} type {}, no usable default",
                userCron, setting.userId, planner.type.key
            )
            return null
        }
        logger.warn(
            "Invalid scan cron '{}' for user {} type {}; using '{}'",
            userCron, setting.userId, planner.type.key, planner.defaultCron
        )
        return default
    }

    private fun parseCron(expression: String): CronExpression? =
        runCatching { CronExpression.parse(expression) }.getOrNull()

    /**
     * This user's non-terminal tasks of [type], handed to the planner so it can suppress a duplicate
     * for work it already queued. An unavailable or failing store yields an empty list rather than
     * skipping the user: worst case a duplicate is proposed, which the runner's own guards then face.
     *
     * The store filters on status: taking a newest-first page and dropping terminal rows in memory
     * would hide a task queued before that page, i.e. exactly the duplicate to suppress.
     */
    private suspend fun nonTerminalTasks(userId: String, type: AsyncTaskType): List<AsyncTask> {
        val store = taskStore ?: return emptyList()
        return runCatching {
            store.listNonTerminal(userId = userId, type = type, limit = PENDING_TASK_SCAN_LIMIT)
        }.onFailure { logger.warn("Failed to list pending {} tasks for {}: {}", type.key, userId, it.message) }
            .getOrDefault(emptyList())
    }

    private companion object {
        /** Same bound the per-type scans used before generalizing; enough for one user's backlog. */
        const val PENDING_TASK_SCAN_LIMIT = 200

        /** Window of the very first scan after start; later scans measure their own elapsed time. */
        const val FIRST_SCAN_LOOKBACK_SECONDS = 60L
    }
}
