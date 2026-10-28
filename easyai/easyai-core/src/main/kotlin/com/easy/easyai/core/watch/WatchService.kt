package com.easy.easyai.core.watch

import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskTrigger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.UUID

/** A watch definition was refused; the message is written for the model or the page to show as-is. */
open class WatchException(message: String) : IllegalStateException(message)

class WatchNotFoundException(message: String) : WatchException(message)

/** The row changed underneath the caller (concurrent edit); reload and retry. */
class WatchConflictException(message: String) : WatchException(message)

/**
 * The definition belongs to another application sharing this database, so this process could neither
 * run it (its agent's tools resolve on the owner) nor honestly report on it.
 */
class WatchForeignHostException(message: String) : WatchException(message)

/** A host dependency is missing, e.g. no task runner is wired. */
class WatchUnavailableException(message: String) : WatchException(message)

/**
 * Creates, edits and administers watch definitions — the one place that validates a schedule and
 * turns a trigger form into an absolute due instant.
 *
 * Nothing here runs the model: the scheduler admits due rows and the handler executes them. This
 * class is the write side of the definition, plus the manual operations a page or the tool can request.
 */
class WatchService(
    private val store: WatchStore,
    private val host: HostIdentity,
    private val settingStore: ScheduledTaskSettingStore? = null,
    private val taskStore: AsyncTaskStore? = null,
    private val runGateway: WatchRunGateway? = null,
    private val properties: WatchProperties = WatchProperties(),
    private val clock: () -> Instant = { Instant.now() }
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    suspend fun create(origin: WatchOrigin, input: WatchCreateInput): WatchCreateResult {
        val now = clock()
        val warnings = mutableListOf<String>()
        val (zone, zoneWarnings) = resolveZone(input.timezone, origin.timeZone)
        warnings += zoneWarnings
        val schedule = resolveSchedule(input.schedule, zone, now)

        val mode = input.mode
        // A reminder never calls a model; only a check (and its expiry review) needs one.
        if (mode != WatchMode.REMIND && origin.modelConfigId.isNullOrBlank()) {
            throw WatchException(
                "A watch needs a saved model configuration; select a model in the composer and retry"
            )
        }
        val reminder = input.reminder?.trim()?.takeIf { it.isNotEmpty() }
        val condition = input.condition.trim().ifEmpty {
            if (mode == WatchMode.REMIND) reminder.orEmpty() else ""
        }
        if (mode == WatchMode.CHECK && condition.isEmpty()) {
            throw WatchException("A check watch needs a 'condition' describing what to verify")
        }
        if (mode == WatchMode.REMIND) {
            if (reminder == null) throw WatchException("A reminder watch needs 'reminder' text")
            if (reminder.length > MAX_REMINDER_CHARS) {
                throw WatchException("'reminder' must be at most $MAX_REMINDER_CHARS characters")
            }
        }
        val title = validateTitle(input.title)
        if (condition.length > MAX_CONDITION_CHARS) {
            throw WatchException("'condition' must be at most $MAX_CONDITION_CHARS characters")
        }
        val tags = normalizeTags(input.tags)
        val notifyTags = input.notifyTags.map { it.trim().lowercase() }.toSet()
        validateNotifyTags(notifyTags, tags)

        val firstDue = WatchScheduleCalculator.firstDue(schedule, now)
        val untilAt = input.until?.let { until ->
            val endOfDay = WatchScheduleCalculator.endOfDay(until, zone)
            if (endOfDay.isBefore(firstDue)) {
                throw WatchException(
                    "'until' must not be before the first run at ${format(firstDue, zone)}"
                )
            }
            endOfDay
        }

        // Repeating the same standing intent is a normal thing for a model to do; returning the live
        // row is better than a duplicate that would double every notification. The schedule is part
        // of the identity: the same wording asked for another time (or another mode) is a different
        // intent, not a retry.
        val duplicate = store.listByUser(origin.userId, setOf(WatchStatus.ACTIVE), properties.maxActivePerUser)
            .firstOrNull {
                it.mode == mode &&
                    conditionKey(it.condition) == conditionKey(condition) &&
                    scheduleKey(it.schedule) == scheduleKey(schedule)
            }
        if (duplicate != null) {
            logger.debug("Watch creation deduplicated onto {} for user {}", duplicate.id, origin.userId)
            return WatchCreateResult(
                watch = duplicate,
                created = false,
                warnings = warnings + "An active watch with the same condition already exists."
            )
        }
        if (store.countActive(origin.userId) >= properties.maxActivePerUser) {
            throw WatchException(
                "This account already has ${properties.maxActivePerUser} active watches; " +
                    "cancel one before creating another"
            )
        }

        val nowMs = now.toEpochMilli()
        // A project-less chat runs in a temporary workspace that the orphan sweep releases. Keeping
        // that path would point every later run at a directory that no longer exists, so the watch
        // starts without it and the caller is told to use durable sources.
        val retainedProjectPath = origin.projectPath?.takeIf { origin.projectKind != ProjectKind.TEMP }
        if (origin.projectKind == ProjectKind.TEMP && origin.projectPath != null) {
            warnings += "This chat's workspace is temporary and is not kept for the life of the watch; " +
                "use URLs or other durable sources for evidence."
        }
        val watch = Watch(
            id = UUID.randomUUID().toString(),
            userId = origin.userId,
            title = title,
            condition = condition,
            mode = mode,
            reminderText = reminder,
            schedule = schedule,
            untilAt = untilAt,
            nextDueAt = firstDue,
            status = WatchStatus.ACTIVE,
            pendingAction = WatchPendingAction.CHECK,
            version = 1,
            extensionCount = 0,
            tags = tags,
            completeOnMet = input.completeOnMet,
            notifyPolicy = input.notifyPolicy,
            notifyTags = notifyTags,
            sourceAgentId = origin.sourceAgentId,
            modelConfigId = origin.modelConfigId,
            projectId = origin.projectId,
            projectPath = retainedProjectPath,
            projectKind = origin.projectKind,
            owners = origin.owners,
            originSessionId = origin.originSessionId,
            runSessionId = UUID.randomUUID().toString(),
            allowedTools = input.allowedTools.map { it.trim() }.filter { it.isNotEmpty() }.distinct(),
            sourceHints = input.sourceHints?.trim()?.takeIf { it.isNotEmpty() },
            createdAt = nowMs,
            updatedAt = nowMs
        )
        val created = store.create(watch)
        ensureScheduledSetting(origin.userId)
        logger.info(
            "Watch created: id={}, user={}, schedule={}, next={}, mode={}, tags={}",
            created.id, origin.userId, schedule.kind, firstDue, mode, tags.size
        )
        return WatchCreateResult(watch = created, created = true, warnings = warnings)
    }

    /**
     * Apply a partial edit.
     *
     * An ACTIVE or PAUSED watch keeps its status; a COMPLETED/EXPIRED one is renewed by a new future
     * schedule and goes back to ACTIVE. A changed definition bumps [Watch.version], which is what lets
     * an in-flight run tell that the definition it started with is gone; a save that changes nothing
     * keeps the version so an open edit dialog cannot cancel a run for free.
     */
    suspend fun update(userId: String, watchId: String, input: WatchUpdateInput): WatchUpdateResult {
        val watch = requireOwned(userId, watchId)
        if (watch.status == WatchStatus.CANCELLED) {
            throw WatchException("This watch was cancelled; create a new one instead of editing it")
        }
        val renewing = watch.status == WatchStatus.COMPLETED || watch.status == WatchStatus.EXPIRED
        if (renewing && input.schedule == null) {
            throw WatchException(
                "This watch is ${watch.status.name.lowercase()}; supply a new 'schedule' to renew it"
            )
        }
        val now = clock()
        val warnings = mutableListOf<String>()

        val mode = input.mode ?: watch.mode
        val reminder = when {
            input.clearReminder -> null
            else -> input.reminder?.let { it.trim().takeIf(String::isNotEmpty) } ?: watch.reminderText
        }
        val condition = (input.condition?.trim() ?: watch.condition).ifEmpty {
            if (mode == WatchMode.REMIND) reminder.orEmpty() else ""
        }
        val title = input.title?.let(::validateTitle) ?: watch.title
        if (mode == WatchMode.CHECK && condition.isEmpty()) {
            throw WatchException("A check watch needs a 'condition' describing what to verify")
        }
        if (mode == WatchMode.REMIND && reminder.isNullOrBlank()) {
            throw WatchException("A reminder watch needs 'reminder' text")
        }
        if (condition.length > MAX_CONDITION_CHARS) {
            throw WatchException("'condition' must be at most $MAX_CONDITION_CHARS characters")
        }
        if (reminder != null && reminder.length > MAX_REMINDER_CHARS) {
            throw WatchException("'reminder' must be at most $MAX_REMINDER_CHARS characters")
        }
        val tags = input.tags?.let(::normalizeTags) ?: watch.tags
        val notifyTags = (input.notifyTags ?: watch.notifyTags).map { it.trim().lowercase() }.toSet()
        validateNotifyTags(notifyTags, tags)

        val zoneChanged = input.timezone != null && input.timezone != watch.schedule.zone
        val zone = when {
            input.schedule != null -> input.timezone ?: watch.schedule.zone
            zoneChanged -> input.timezone!!
            else -> watch.schedule.zone
        }
        val schedule = when {
            input.schedule != null -> resolveSchedule(input.schedule, zone, now)
            zoneChanged -> watch.schedule.copy(zone = zone)
            else -> watch.schedule
        }
        val scheduleMoved = input.schedule != null || zoneChanged
        var nextDue = if (scheduleMoved) WatchScheduleCalculator.firstDue(schedule, now) else watch.nextDueAt

        var untilAt = when {
            input.clearUntil -> null
            input.until != null -> WatchScheduleCalculator.endOfDay(input.until, zone)
            else -> watch.untilAt
        }
        if (renewing) {
            // Carrying a dead deadline into the new lifecycle would expire the watch at once. This
            // covers a date the caller supplied too: the edit page prefills the old until, and
            // refusing the save for it reads as "save does nothing".
            if (!input.clearUntil && untilAt != null && !untilAt.isAfter(nextDue)) {
                warnings += "The until date was before the new first run, so it was cleared."
                untilAt = null
            }
        }

        val untilChanged = when {
            input.clearUntil -> watch.untilAt != null
            input.until != null -> untilAt != watch.untilAt
            else -> false
        }
        val deadlineMoved = untilChanged || scheduleMoved
        if (untilChanged && watch.pendingAction == WatchPendingAction.EXPIRY_REVIEW &&
            schedule.kind != WatchScheduleKind.AT
        ) {
            // Ending a pending expiry review drops its short guard from next_due_at; the recurrence
            // owns the next run again. A one-shot keeps the guard: its anchor is already in the past
            // and rescheduling to it would fire immediately.
            nextDue = WatchScheduleCalculator.nextAfter(schedule, now)
        }
        if (untilAt != null && untilAt.isBefore(nextDue)) {
            throw WatchException(
                "'until' must be after the next run at ${format(nextDue, zone)}; " +
                    "use a later date, or clear it"
            )
        }

        val pendingAction = if (watch.pendingAction == WatchPendingAction.EXPIRY_REVIEW && !deadlineMoved) {
            WatchPendingAction.EXPIRY_REVIEW
        } else {
            WatchPendingAction.CHECK
        }
        val status = if (renewing) WatchStatus.ACTIVE else watch.status
        val updated = watch.copy(
            title = title,
            condition = condition,
            mode = mode,
            reminderText = reminder,
            schedule = schedule,
            untilAt = untilAt,
            nextDueAt = nextDue,
            status = status,
            pendingAction = pendingAction,
            version = watch.version + 1,
            extensionCount = if (renewing) 0 else watch.extensionCount,
            tags = tags,
            completeOnMet = input.completeOnMet ?: watch.completeOnMet,
            notifyPolicy = input.notifyPolicy ?: watch.notifyPolicy,
            notifyTags = notifyTags,
            allowedTools = input.allowedTools?.map { it.trim() }?.filter { it.isNotEmpty() }?.distinct()
                ?: watch.allowedTools,
            sourceHints = when {
                input.clearSourceHints -> null
                input.sourceHints != null -> input.sourceHints.trim().takeIf { it.isNotEmpty() }
                else -> watch.sourceHints
            },
            consecutiveFailures = if (renewing) 0 else watch.consecutiveFailures,
            lastError = if (renewing) null else watch.lastError,
            statusReason = if (renewing) null else watch.statusReason,
            updatedAt = now.toEpochMilli()
        )
        if (updated.copy(version = watch.version, updatedAt = watch.updatedAt) == watch) {
            // Nothing to save: keeping the version means an unchanged dialog cannot invalidate a run
            // that is executing right now.
            logger.debug("Watch edit changed nothing: id={}, user={}", watchId, userId)
            return WatchUpdateResult(watch = watch, warnings = warnings)
        }
        val saved = store.updateDefinition(updated, watch.version, watch.status)
            ?: throw WatchConflictException(
                "This watch changed while the edit was being applied; reload it and try again"
            )
        if (renewing) ensureScheduledSetting(userId)
        logger.info(
            "Watch updated: id={}, user={}, version={}, status={}, next={}, pending={}",
            saved.id, userId, saved.version, saved.status, saved.nextDueAt, saved.pendingAction
        )
        return WatchUpdateResult(watch = saved, warnings = warnings)
    }

    suspend fun list(
        userId: String,
        statuses: Set<WatchStatus>? = null,
        limit: Int = 100
    ): List<Watch> = store.listByUser(userId, statuses, limit)

    suspend fun get(userId: String, watchId: String): Watch = requireOwned(userId, watchId)

    suspend fun pause(userId: String, watchId: String): Watch {
        val watch = requireOwned(userId, watchId)
        if (watch.status != WatchStatus.ACTIVE) {
            throw WatchException("Only an active watch can be paused (this one is ${watch.status.name.lowercase()})")
        }
        return store.setStatus(watchId, WatchStatus.PAUSED, "Paused by the user", expectedStatus = watch.status)
            ?: throw WatchConflictException("This watch changed while it was being paused; reload and try again")
    }

    suspend fun resume(userId: String, watchId: String): Watch {
        val watch = requireOwned(userId, watchId)
        if (watch.status != WatchStatus.PAUSED) {
            throw WatchException("Only a paused watch can be resumed (this one is ${watch.status.name.lowercase()})")
        }
        return store.setStatus(
            watchId, WatchStatus.ACTIVE, null, expectedStatus = watch.status, clearFailures = true
        ) ?: throw WatchConflictException("This watch changed while it was being resumed; reload and try again")
    }

    suspend fun cancel(userId: String, watchId: String): Watch {
        val watch = requireOwned(userId, watchId)
        val cancelled = closeAdmission(watch)
        cancelInFlight(cancelled)
        return cancelled
    }

    suspend fun delete(userId: String, watchId: String): Boolean {
        val watch = requireOwned(userId, watchId)
        val cancelled = closeAdmission(watch)
        cancelInFlight(cancelled)
        return store.delete(watchId)
    }

    private suspend fun closeAdmission(watch: Watch): Watch {
        if (watch.status == WatchStatus.CANCELLED) return watch
        return store.setStatus(
            watch.id, WatchStatus.CANCELLED, "Cancelled by the user", expectedStatus = watch.status
        ) ?: throw WatchConflictException("This watch changed while it was being cancelled; reload and try again")
    }

    /** Start a check now without moving the schedule; the same one-run-at-a-time rule applies. */
    suspend fun runNow(userId: String, watchId: String, trigger: TaskTrigger = TaskTrigger.FRONTEND): AsyncTask {
        val watch = requireOwned(userId, watchId)
        if (watch.status != WatchStatus.ACTIVE) {
            throw WatchException("Only an active watch can be run now (this one is ${watch.status.name.lowercase()})")
        }
        if (watch.pendingAction != WatchPendingAction.CHECK) {
            throw WatchException("An expiry review is pending for this watch; wait for it or edit the schedule")
        }
        val gateway = runGateway ?: throw WatchUnavailableException(
            "The background task runner is not available, so the watch cannot be started now"
        )
        // Manual rows are not recovered at startup: cancellation must not split commit from dispatch.
        return withContext(NonCancellable) {
            when (val result = store.enqueueManual(userId, watchId, clock(), properties, trigger)) {
                is WatchEnqueueResult.Enqueued -> result.task.also { gateway.dispatch(it) }
                is WatchEnqueueResult.Busy -> throw WatchException("A run is already in progress for this watch")
                WatchEnqueueResult.Stale -> throw WatchConflictException(
                    "This watch changed before the run was queued; reload and try again"
                )
            }
        }
    }

    /** Retry an authorized task snapshot without changing its original firing payload or schedule. */
    suspend fun retryRun(userId: String, task: AsyncTask): AsyncTask {
        if (task.type != AsyncTaskType.WATCH) throw WatchException("Only a watch task can be retried here")
        if (task.userId != userId) throw WatchNotFoundException("Watch task ${task.id} was not found")
        if (!host.owns(task.ownerApp)) {
            throw WatchForeignHostException("Watch task ${task.id} does not belong to application '${host.appName}'")
        }
        val watchId = WatchTaskPayload.watchIdOf(task)
            ?: throw WatchException("Watch task ${task.id} has no watch id in its payload")
        requireOwned(userId, watchId)
        if (!task.status.isTerminal) throw WatchConflictException("Only a finished task can be retried")
        val gateway = runGateway ?: throw WatchUnavailableException(
            "The background task runner is not available, so the watch cannot be retried"
        )
        // The old worker can still be unwinding after its terminal write; never wait under a DB lock.
        gateway.awaitIdle(task.id)
        return withContext(NonCancellable) {
            when (val result = store.retryRun(userId, watchId, task.id, task.retryCount, clock())) {
                is WatchEnqueueResult.Enqueued -> result.task.also { gateway.dispatch(it) }
                is WatchEnqueueResult.Busy -> throw WatchConflictException("A run is already in progress for this watch")
                WatchEnqueueResult.Stale -> throw WatchConflictException(
                    "This watch or task changed before it could be retried; reload and try again"
                )
            }
        }
    }

    /** Ensure a scan row exists for this user and is on, without touching an existing schedule. */
    suspend fun ensureScheduledSetting(userId: String) {
        val settings = settingStore ?: return
        runCatching {
            val existing = settings.get(userId, AsyncTaskType.WATCH)
            if (existing == null) {
                settings.save(
                    ScheduledTaskSetting(
                        userId = userId,
                        taskType = AsyncTaskType.WATCH,
                        enabled = true
                    )
                )
            } else if (!existing.enabled) {
                settings.save(existing.copy(enabled = true, updatedAt = System.currentTimeMillis()))
            }
        }.onFailure {
            // The definition is still valid; a missing scan row only means it never fires, so this is
            // worth a loud log rather than failing a creation the user already saw.
            logger.error("Failed to enable watch scheduling for user {}: {}", userId, it.message, it)
        }
    }

    /**
     * The caller's watch, or the reason it is not theirs.
     *
     * Two different refusals: another user's row is not found (it must not be distinguishable from a
     * deleted one), while a row this application cannot run is reported as such — the page needs to
     * say why instead of pretending the watch never existed.
     *
     * An unowned row is refused too, not treated as fair game. Ownership is stamped on insert, so a
     * null owner means the row predates the column; letting both applications edit or fire it would
     * recreate exactly the double-run this column exists to prevent. Adoption or the documented
     * backfill claims it once, and then it is ordinary again.
     */
    private suspend fun requireOwned(userId: String, watchId: String): Watch {
        val watch = store.findById(watchId)?.takeIf { it.userId == userId }
            ?: throw WatchNotFoundException("Watch $watchId was not found")
        val owner = watch.ownerApp
        if (!host.owns(owner)) {
            throw WatchForeignHostException(
                if (owner == null) {
                    "Watch '${watch.title}' does not belong to an application yet; it must be claimed " +
                        "(owner_app backfill or startup adoption) before it can be viewed, edited or run"
                } else {
                    "Watch '${watch.title}' belongs to application '$owner' and cannot be viewed, " +
                        "edited or run from '${host.appName}'"
                }
            )
        }
        return watch
    }

    private suspend fun cancelInFlight(watch: Watch) {
        store.listInFlight(watch.userId, watch.id).forEach { task ->
            var cancelled = false
            val gateway = runGateway
            if (gateway != null) {
                cancelled = gateway.cancel(task.id)
            } else {
                cancelled = taskStore?.cancel(task.id) != null
            }
            if (!cancelled) logger.warn("In-flight watch task {} could not be cancelled", task.id)
        }
    }

    private fun resolveZone(requested: ZoneId?, contextZone: ZoneId?): Pair<ZoneId, List<String>> {
        requested?.let { return it to emptyList() }
        contextZone?.let { return it to emptyList() }
        val fallback = ZoneId.systemDefault()
        return fallback to listOf(
            "No time zone was supplied by the request or the agent, so the server time zone " +
                "($fallback) was used. Confirm the resulting time with the user."
        )
    }

    /**
     * Validate one trigger form and resolve it. Every rejection names what to do instead: these
     * messages travel back to the model, which has to be able to fix the call.
     */
    private fun resolveSchedule(input: WatchScheduleInput, zone: ZoneId, now: Instant): WatchSchedule {
        when (input) {
            is WatchScheduleInput.At -> {
                if (!input.instant.isAfter(now)) {
                    throw WatchException(
                        "'at' must be in the future; ${format(input.instant, zone)} has already passed"
                    )
                }
            }
            is WatchScheduleInput.In -> {
                if (input.duration < MIN_LEAD_TIME) {
                    throw WatchException("'in' must be at least one minute from now")
                }
                if (input.duration > MAX_LEAD_TIME) {
                    throw WatchException("'in' must be at most one year from now")
                }
            }
            is WatchScheduleInput.DayOffset -> {
                if (input.days !in 0..MAX_DAY_OFFSET) {
                    throw WatchException("'day_offset' must be between 0 and $MAX_DAY_OFFSET")
                }
                val candidate = WatchScheduleCalculator.resolve(input, zone, now)
                val instant = WatchScheduleCalculator.firstDue(candidate, now)
                if (!instant.isAfter(now)) {
                    throw WatchException(
                        "${input.time} has already passed today in ${zone.id}; " +
                            "use day_offset=1 for tomorrow or a later time"
                    )
                }
            }
            is WatchScheduleInput.WeekdayOnce -> Unit
            is WatchScheduleInput.Every -> {
                when (input.kind) {
                    WatchScheduleKind.AT -> throw WatchException(
                        "'every' cannot be 'at'; use 'at' or 'in' for a one-shot trigger"
                    )
                    WatchScheduleKind.DAILY -> if (input.weekday != null || input.dayOfMonth != null) {
                        throw WatchException("A daily watch takes only 'time'; drop weekday/day_of_month")
                    }
                    WatchScheduleKind.WEEKLY -> if (input.weekday == null) {
                        throw WatchException("A weekly watch needs 'weekday' (MONDAY..SUNDAY)")
                    }
                    WatchScheduleKind.MONTHLY -> if (input.dayOfMonth != null && input.dayOfMonth !in 1..31) {
                        throw WatchException("'day_of_month' must be between 1 and 31")
                    }
                }
            }
        }
        return WatchScheduleCalculator.resolve(input, zone, now)
    }

    private fun validateTitle(title: String): String {
        val trimmed = title.trim()
        if (trimmed.isEmpty()) throw WatchException("'title' must not be empty")
        if (trimmed.length > MAX_TITLE_CHARS) {
            throw WatchException("'title' must be at most $MAX_TITLE_CHARS characters")
        }
        return trimmed
    }

    private fun normalizeTags(tags: List<WatchTag>): List<WatchTag> {
        if (tags.size > MAX_TAGS) throw WatchException("A watch can define at most $MAX_TAGS tags")
        val seen = mutableSetOf<String>()
        return tags.map { tag ->
            val key = tag.key.trim().lowercase()
            val label = tag.label.trim()
            if (!TAG_KEY_PATTERN.matches(key)) {
                throw WatchException(
                    "Tag key '$key' is invalid; use lowercase letters, digits and underscores, " +
                        "starting with a letter (2-32 characters)"
                )
            }
            if (!seen.add(key)) throw WatchException("Tag key '$key' is defined twice")
            if (label.isEmpty()) throw WatchException("Tag '$key' needs a label")
            if (label.length > MAX_TAG_LABEL_CHARS) {
                throw WatchException("Tag '$key' label must be at most $MAX_TAG_LABEL_CHARS characters")
            }
            val description = tag.description?.trim()?.takeIf { it.isNotEmpty() }
            if (description != null && description.length > MAX_TAG_DESCRIPTION_CHARS) {
                throw WatchException(
                    "Tag '$key' description must be at most $MAX_TAG_DESCRIPTION_CHARS characters"
                )
            }
            WatchTag(key = key, label = label, description = description)
        }
    }

    private fun validateNotifyTags(notifyTags: Set<String>, tags: List<WatchTag>) {
        val defined = tags.map { it.key }.toSet()
        val unknown = notifyTags - defined
        if (unknown.isNotEmpty()) {
            throw WatchException(
                "notify_tags refer to undefined tags: ${unknown.sorted().joinToString(", ")}; " +
                    "define them in 'tags' first"
            )
        }
    }

    private fun conditionKey(condition: String): String =
        condition.trim().replace(WHITESPACE, " ").lowercase()

    /**
     * Identity of a schedule for duplicate detection. A one-shot instant is compared at minute
     * precision so a relative trigger (`within`) retried seconds later still reads as the same
     * intent, while the same wording at another time of day does not.
     */
    private fun scheduleKey(schedule: WatchSchedule): String = when (schedule.kind) {
        WatchScheduleKind.AT -> {
            val local = schedule.anchorAt?.atZone(schedule.zone)?.toLocalDateTime()
                ?.truncatedTo(ChronoUnit.MINUTES)
            "AT:$local"
        }
        else -> "${schedule.kind}:${schedule.zone.id}:${schedule.localTime}:" +
            "${schedule.weekday}:${schedule.dayOfMonth}"
    }

    private fun format(instant: Instant, zone: ZoneId): String =
        DISPLAY_FORMAT.withZone(zone).format(instant)

    companion object {
        private const val MAX_TITLE_CHARS = 200
        private const val MAX_CONDITION_CHARS = 2000
        private const val MAX_REMINDER_CHARS = 500
        private const val MAX_TAGS = 8
        private const val MAX_TAG_LABEL_CHARS = 40
        private const val MAX_TAG_DESCRIPTION_CHARS = 200
        private const val MAX_DAY_OFFSET = 3650

        private val MIN_LEAD_TIME: Duration = Duration.ofMinutes(1)
        private val MAX_LEAD_TIME: Duration = Duration.ofDays(365)
        private val TAG_KEY_PATTERN = Regex("^[a-z][a-z0-9_]{1,31}$")
        private val WHITESPACE = Regex("\\s+")
        private val DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm z")

        /** Title shown on the Tasks page for a watch firing. */
        @JvmStatic
        fun taskTitle(watch: Watch): String = "Watch · ${watch.title}"
    }
}
