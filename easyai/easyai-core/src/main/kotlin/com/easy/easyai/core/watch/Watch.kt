package com.easy.easyai.core.watch

import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.TaskTrigger
import com.easy.easyai.core.tool.ToolDefinition
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/** What a firing is meant to do: gather evidence and judge, or just deliver a reminder. */
enum class WatchMode { CHECK, REMIND }

enum class WatchScheduleKind { AT, DAILY, WEEKLY, MONTHLY }

enum class WatchStatus { ACTIVE, PAUSED, COMPLETED, EXPIRED, CANCELLED }

/** The next thing the planner should do when the watch comes due. */
enum class WatchPendingAction { CHECK, EXPIRY_REVIEW }

/** Machine-readable outcome of one check. Tags carry the human vocabulary; this drives code. */
enum class WatchVerdict { MET, NOT_MET, UNKNOWN, NOTE }

enum class WatchNotifyPolicy { ON_CHANGE, ON_COMPLETE, EVERY, NEVER }

enum class WatchRunKind { CHECK, EXPIRY_REVIEW }

enum class WatchExpiryDecision { RENEW, COMPLETE, EXPIRE }

/** One value of the universe the creator defines; a finding may only use these keys. */
data class WatchTag(
    val key: String,
    val label: String,
    val description: String? = null
)

data class WatchSource(
    val label: String,
    val url: String
)

/** A tag on a stored finding, with the label snapshot taken when it was written. */
data class WatchFoundTag(
    val key: String,
    val label: String
)

/**
 * One conclusion. [definitionVersion] is the watch version the run started with: a finding whose
 * version no longer matches the live watch is kept in task history but must not change watch state.
 */
data class WatchFinding(
    val conclusion: String,
    val verdict: WatchVerdict,
    val tags: List<WatchFoundTag> = emptyList(),
    val sources: List<WatchSource> = emptyList(),
    val definitionVersion: Int = 0,
    val changed: Boolean = false,
    val notified: Boolean = false,
    val scheduledFor: Long? = null,
    val lateByMs: Long = 0,
    val applied: Boolean = true,
    val stale: Boolean = false
)

/** The recurrence rule itself, independent of the one-shot anchor. */
data class WatchSchedule(
    val kind: WatchScheduleKind,
    val zone: ZoneId,
    val localTime: LocalTime? = null,
    val weekday: DayOfWeek? = null,
    val dayOfMonth: Int? = null,
    val anchorAt: Instant? = null
)

/**
 * A durable standing intent. [runSessionId] is a hidden session shared by every firing, so the
 * transcript and the task rows form one audit trail; [version] is bumped by every edit and is the
 * guard that keeps an in-flight finding from overwriting a definition the user just changed.
 */
data class Watch(
    val id: String,
    val userId: String,
    val title: String,
    val condition: String,
    val mode: WatchMode,
    val reminderText: String?,
    val schedule: WatchSchedule,
    val untilAt: Instant?,
    val nextDueAt: Instant,
    val status: WatchStatus,
    val pendingAction: WatchPendingAction = WatchPendingAction.CHECK,
    val version: Int = 1,
    val extensionCount: Int = 0,
    val tags: List<WatchTag>,
    val completeOnMet: Boolean,
    val notifyPolicy: WatchNotifyPolicy,
    val notifyTags: Set<String>,
    val sourceAgentId: String,
    val modelConfigId: String?,
    val projectId: String?,
    val projectPath: String?,
    val projectKind: ProjectKind,
    val owners: Set<String>,
    val originSessionId: String?,
    val runSessionId: String,
    val allowedTools: List<String>,
    val sourceHints: String?,
    val lastTaskId: String? = null,
    val lastRunAt: Long? = null,
    val lastFinding: WatchFinding? = null,
    val consecutiveFailures: Int = 0,
    val lastError: String? = null,
    val statusReason: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)

/** A due watch the claim policy decided to run, with the schedule mutation that goes with it. */
data class WatchClaim(
    val watch: Watch,
    val kind: WatchRunKind,
    val scheduledFor: Instant,
    val lateByMs: Long,
    val newNextDueAt: Instant,
    val pendingAction: WatchPendingAction
)

/** Where a watch was created: everything the unattended run needs but no request context carries. */
data class WatchOrigin(
    val userId: String,
    val sourceAgentId: String,
    val modelConfigId: String?,
    val projectId: String?,
    val projectPath: String?,
    val projectKind: ProjectKind,
    val owners: Set<String>,
    val originSessionId: String?,
    val timeZone: ZoneId?
)

/** The trigger form accepted by the tool; resolved to an absolute instant before it is persisted. */
sealed interface WatchScheduleInput {
    data class At(val instant: Instant) : WatchScheduleInput
    data class In(val duration: Duration) : WatchScheduleInput
    data class DayOffset(val days: Int, val time: LocalTime) : WatchScheduleInput
    data class WeekdayOnce(val weekday: DayOfWeek, val time: LocalTime) : WatchScheduleInput
    data class Every(
        val kind: WatchScheduleKind,
        val time: LocalTime,
        val weekday: DayOfWeek? = null,
        val dayOfMonth: Int? = null
    ) : WatchScheduleInput
}

data class WatchCreateInput(
    val title: String,
    val condition: String,
    val schedule: WatchScheduleInput,
    val mode: WatchMode = WatchMode.CHECK,
    val reminder: String? = null,
    val timezone: ZoneId? = null,
    /** Calendar date in the watch's zone; the store receives the end of that day. */
    val until: LocalDate? = null,
    val tags: List<WatchTag> = emptyList(),
    val completeOnMet: Boolean = true,
    val notifyPolicy: WatchNotifyPolicy = WatchNotifyPolicy.ON_CHANGE,
    val notifyTags: Set<String> = emptySet(),
    val sourceHints: String? = null,
    val allowedTools: List<String> = emptyList()
)

/**
 * A partial edit. Null means "leave it alone"; the explicit clear flags exist because a nullable
 * field cannot distinguish "not supplied" from "remove the deadline".
 */
data class WatchUpdateInput(
    val title: String? = null,
    val condition: String? = null,
    val mode: WatchMode? = null,
    val reminder: String? = null,
    val clearReminder: Boolean = false,
    val schedule: WatchScheduleInput? = null,
    val timezone: ZoneId? = null,
    val until: LocalDate? = null,
    val clearUntil: Boolean = false,
    val tags: List<WatchTag>? = null,
    val completeOnMet: Boolean? = null,
    val notifyPolicy: WatchNotifyPolicy? = null,
    val notifyTags: Set<String>? = null,
    val sourceHints: String? = null,
    val clearSourceHints: Boolean = false,
    val allowedTools: List<String>? = null
)

data class WatchCreateResult(
    val watch: Watch,
    /** False when an identical active watch already existed and was returned instead. */
    val created: Boolean,
    val warnings: List<String> = emptyList()
)

data class WatchUpdateResult(
    val watch: Watch,
    val warnings: List<String> = emptyList()
)

/** A notification request; the channel chooses the transport and the deep link. */
data class WatchNotice(
    val userId: String,
    val watchId: String,
    val title: String,
    val body: String?,
    /** `finding`, `expiry_review`, or `status`. */
    val kind: String,
    val dedupeKey: String
)

data class WatchScheduleEntry(
    val watchId: String,
    val userId: String,
    val version: Int,
    val nextDueAt: Instant,
    val pendingAction: WatchPendingAction
)

data class WatchScheduleCursor(val nextDueAt: Instant, val watchId: String)

data class WatchScheduleChanged(val watchId: String, val userId: String)

sealed interface WatchEnqueueResult {
    data class Enqueued(val task: AsyncTask) : WatchEnqueueResult
    data class Busy(val taskId: String) : WatchEnqueueResult
    data object Stale : WatchEnqueueResult
}

/** Persistence for [Watch] definitions, keyed by the owning user. */
interface WatchStore {

    suspend fun create(watch: Watch): Watch

    suspend fun findById(id: String): Watch?

    suspend fun listByUser(
        userId: String,
        statuses: Set<WatchStatus>? = null,
        limit: Int = 100
    ): List<Watch>

    suspend fun countActive(userId: String): Int

    suspend fun listUpcoming(
        horizon: Instant,
        cursor: WatchScheduleCursor? = null,
        limit: Int = 200,
        userId: String? = null
    ): List<WatchScheduleEntry>

    suspend fun findSchedule(watchId: String): WatchScheduleEntry?

    suspend fun enqueueScheduled(
        expected: WatchScheduleEntry,
        now: Instant,
        properties: WatchProperties
    ): WatchEnqueueResult

    suspend fun enqueueManual(
        userId: String,
        watchId: String,
        now: Instant,
        properties: WatchProperties,
        trigger: TaskTrigger = TaskTrigger.FRONTEND
    ): WatchEnqueueResult

    suspend fun retryRun(
        userId: String,
        watchId: String,
        taskId: String,
        expectedRetryCount: Int,
        now: Instant
    ): WatchEnqueueResult

    suspend fun listInFlight(userId: String, watchId: String): List<AsyncTask>

    suspend fun claimRunSlot(task: AsyncTask, expectedVersion: Int, at: Instant): Boolean

    /**
     * Apply a check result, only while the row still has [expectedVersion], [expectedStatus], and
     * [taskId] is still the run that [claimRunSlot] last claimed. Null means a guard rejected the
     * write (edited, paused, cancelled, completed, or another run took the slot).
     */
    suspend fun applyFinding(
        id: String,
        expectedVersion: Int,
        expectedStatus: WatchStatus,
        status: WatchStatus,
        pendingAction: WatchPendingAction,
        nextDueAt: Long,
        finding: WatchFinding,
        taskId: String,
        at: Long
    ): Watch?

    suspend fun recordFailure(
        id: String,
        expectedVersion: Int,
        expectedStatus: WatchStatus,
        error: String,
        nextDueAt: Long,
        status: WatchStatus,
        consecutiveFailures: Int,
        statusReason: String?,
        taskId: String?,
        at: Long
    ): Watch?

    /** Same guards as [applyFinding], including the task slot claimed by [claimRunSlot]. */
    suspend fun applyExpiryReview(
        id: String,
        expectedVersion: Int,
        expectedPendingAction: WatchPendingAction,
        status: WatchStatus,
        pendingAction: WatchPendingAction,
        untilAt: Long?,
        anchorAt: Long?,
        nextDueAt: Long,
        extensionCount: Int,
        statusReason: String?,
        taskId: String,
        at: Long
    ): Watch?

    /**
     * Replace the definition fields guarded by [expectedVersion] and [expectedStatus]; never touches
     * run history. The status guard keeps a cancel that landed between the caller's read and this
     * write from being undone by an edit built on the earlier state.
     */
    suspend fun updateDefinition(watch: Watch, expectedVersion: Int, expectedStatus: WatchStatus): Watch?

    /**
     * Pause/resume/cancel. Ownership is checked by the caller; no version bump. [expectedStatus]
     * makes the transition conditional on the state the caller read, so a pause cannot overwrite a
     * run that completed in between and a stale resume cannot revive a cancelled watch.
     */
    suspend fun setStatus(
        id: String,
        status: WatchStatus,
        statusReason: String?,
        pendingAction: WatchPendingAction? = null,
        nextDueAt: Long? = null,
        expectedStatus: WatchStatus? = null,
        /** An explicit resume is a fresh start: clear the failure streak the pause was based on. */
        clearFailures: Boolean = false
    ): Watch?

    suspend fun delete(id: String): Boolean
}

/**
 * Keys of the `async_task.payload` a watch firing carries. Self-sufficient on purpose: a task retried
 * days later must not depend on the watch's current schedule, only on its id and the run kind.
 */
object WatchTaskPayload {
    const val KEY_WATCH_ID = "watchId"
    const val KEY_KIND = "kind"
    const val KEY_SCHEDULED_FOR = "scheduledFor"

    /**
     * Diagnostic only: the version the planner saw when it claimed the row. The handler re-reads the
     * watch at execution time and guards on that version instead, because a task can be retried long
     * after the definition moved.
     */
    const val KEY_DEFINITION_VERSION = "definitionVersion"
    /** The watch title, so the Tasks page can localize its own label around it. */
    const val KEY_TITLE = "title"

    fun watchIdOf(task: com.easy.easyai.core.task.AsyncTask): String? =
        task.payload[KEY_WATCH_ID] as? String

    fun kindOf(task: com.easy.easyai.core.task.AsyncTask): WatchRunKind {
        val raw = task.payload[KEY_KIND] as? String
        return WatchRunKind.entries.firstOrNull { it.name == raw?.uppercase() } ?: WatchRunKind.CHECK
    }

    fun scheduledForOf(task: com.easy.easyai.core.task.AsyncTask): Long? =
        (task.payload[KEY_SCHEDULED_FOR] as? Number)?.toLong()
}

/** The delivery channel for a watch notice. Implementations must never throw. */
fun interface WatchNotifier {
    suspend fun publish(notice: WatchNotice)
}

/** Resolves the read-only tool set a watch run may use, re-checking it against the live agent. */
fun interface WatchToolsetResolver {
    suspend fun resolve(watch: Watch, runSessionId: String): List<ToolDefinition>
}

/**
 * Starts and stops the async-task rows a watch owns. Wired to the runner in the host so a user
 * cancel interrupts the live worker instead of only flipping the row's status.
 */
interface WatchRunGateway {
    fun dispatch(task: AsyncTask)
    suspend fun awaitIdle(taskId: String)
    suspend fun cancel(taskId: String): Boolean
}

/** Deployment defaults and hard caps for the watch subsystem. */
data class WatchProperties(
    /** Deployment-wide kill switch; false stops planning and fails no existing row. */
    val enabled: Boolean = true,

    /** Hard cap on ACTIVE definitions per user; an account belongs to exactly one application. */
    val maxActivePerUser: Int = 50,
    val runTimeoutMs: Long = 180_000,
    val maxIterations: Int = 12,
    val expiryReviewMaxIterations: Int = 6,
    val maxAutoExtensions: Int = 1,
    val maxExtensionHorizonDays: Long = 90,
    val expiryReviewGuardMs: Long = 600_000,
    val catchUpWindowMs: Long = 86_400_000,
    val oneShotRetryGuardMs: Long = 600_000,
    val failureBackoffBaseMs: Long = 3_600_000,
    val failureBackoffMaxMs: Long = 86_400_000,
    val maxFailuresBeforePause: Int = 3,
    val perUserPerMinuteLimit: Int = 20
)
