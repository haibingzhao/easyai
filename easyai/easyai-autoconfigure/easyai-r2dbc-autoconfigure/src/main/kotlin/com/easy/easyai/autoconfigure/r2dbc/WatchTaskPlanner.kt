package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.TaskDraft
import com.easy.easyai.core.watch.WatchClaimPolicy
import com.easy.easyai.core.watch.WatchProperties
import com.easy.easyai.core.watch.WatchService
import com.easy.easyai.core.watch.WatchStore
import com.easy.easyai.core.watch.WatchTaskPayload
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * Answers "which watches are due for this user" for [ScheduledTaskRunner].
 *
 * **This is the one planner that mutates its source rows while planning.** A watch firing is
 * claimed here — `next_due_at` moves forward under `WHERE next_due_at = <the value read>` before the
 * task row exists — because otherwise two ticks, or a host that resolves pending tasks late, would
 * enqueue the same firing twice. A lost claim is the normal way two planners agree; the loser just
 * proposes nothing.
 *
 * The claim also decides check vs expiry review ([WatchClaimPolicy]) and gives an in-flight run its
 * guard time. A watch with an unsettled task is skipped for this tick: one watch may have only one
 * run in flight, which is what keeps a slow run from stacking up a backlog of its own firings.
 */
class WatchTaskPlanner(
    private val store: WatchStore,
    private val properties: WatchProperties = WatchProperties(),
    private val clock: () -> Instant = { Instant.now() }
) : ScheduledTaskPlanner {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val type: AsyncTaskType = AsyncTaskType.WATCH

    override val defaultCron: String get() = properties.scanCron

    override val perUserPerTickLimit: Int get() = properties.perUserPerTickLimit

    override suspend fun plan(context: ScheduledTaskPlanContext): List<TaskDraft> {
        if (!properties.enabled) return emptyList()
        val now = clock()
        val pendingWatchIds = context.pendingTasks.mapNotNull { WatchTaskPayload.watchIdOf(it) }.toSet()
        val due = store.listDue(
            userId = context.userId,
            now = now.toEpochMilli(),
            limit = properties.perUserPerTickLimit * CLAIM_ATTEMPT_FACTOR
        )
        if (due.isEmpty()) return emptyList()
        val drafts = mutableListOf<TaskDraft>()
        for (watch in due) {
            // The runner submits at most `perUserPerTickLimit` drafts, so claiming beyond that would
            // advance a firing with no task behind it. Extra rows are only read so a lost claim does
            // not starve the rest of this user's due watches.
            if (drafts.size >= properties.perUserPerTickLimit) break
            if (watch.id in pendingWatchIds) {
                logger.debug("Watch {} still has an unsettled run; skipping this tick", watch.id)
                continue
            }
            val scheduledFor = watch.nextDueAt
            val decision = WatchClaimPolicy.decide(watch, now, properties) ?: continue
            // The task row carries the firing's original instant: the handler reports lateness from
            // it, and a retry days later must still know when this run was meant to happen.
            val claimed = store.claim(
                id = watch.id,
                expectedNextDueAt = scheduledFor.toEpochMilli(),
                newNextDueAt = decision.newNextDueAt.toEpochMilli(),
                pendingAction = decision.pendingAction,
                statusReason = decision.statusReason
            )
            if (!claimed) {
                logger.debug("Watch {} was claimed by another tick; skipping", watch.id)
                continue
            }
            drafts += TaskDraft(
                userId = watch.userId,
                type = AsyncTaskType.WATCH,
                title = WatchService.taskTitle(watch),
                sessionId = watch.runSessionId,
                timeoutMs = properties.runTimeoutMs,
                payload = mapOf(
                    WatchTaskPayload.KEY_WATCH_ID to watch.id,
                    WatchTaskPayload.KEY_KIND to decision.kind.name,
                    WatchTaskPayload.KEY_SCHEDULED_FOR to scheduledFor.toEpochMilli(),
                    WatchTaskPayload.KEY_DEFINITION_VERSION to watch.version,
                    WatchTaskPayload.KEY_TITLE to watch.title
                )
            )
        }
        if (drafts.isNotEmpty()) {
            logger.info(
                "Watch plans for user {}: {} due, {} claimed",
                context.userId, due.size, drafts.size
            )
        }
        return drafts
    }

    private companion object {
        /**
         * Claims can be lost to a concurrent tick, and skipped rows are not retried inside this
         * pass; reading a few more than the submission cap keeps one lost race from starving the
         * rest of the user's due watches.
         */
        const val CLAIM_ATTEMPT_FACTOR = 4
    }
}
