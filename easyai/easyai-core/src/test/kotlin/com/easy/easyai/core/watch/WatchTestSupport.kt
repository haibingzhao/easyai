package com.easy.easyai.core.watch

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.ChatSession
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.event.EventStream
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.ProjectKind
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskStatus
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.TaskTrigger
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId

/** Shared fixtures for the watch run tests: a mutable watch row, a recording store, and a model stub. */
internal const val TEST_APP = "agent-api"

internal fun testWatch(
    id: String = "watch-1",
    userId: String = "alice",
    status: WatchStatus = WatchStatus.ACTIVE,
    mode: WatchMode = WatchMode.CHECK,
    schedule: WatchSchedule = WatchSchedule(
        kind = WatchScheduleKind.DAILY,
        zone = ZoneId.of("Asia/Shanghai"),
        localTime = LocalTime.of(9, 0)
    ),
    nextDueAt: Instant = Instant.parse("2026-10-25T01:00:00Z"),
    untilAt: Instant? = null,
    pendingAction: WatchPendingAction = WatchPendingAction.CHECK,
    version: Int = 3,
    extensionCount: Int = 0,
    tags: List<WatchTag> = listOf(WatchTag("crossed", "已突破")),
    completeOnMet: Boolean = true,
    notifyPolicy: WatchNotifyPolicy = WatchNotifyPolicy.ON_CHANGE,
    notifyTags: Set<String> = emptySet(),
    lastFinding: WatchFinding? = null,
    consecutiveFailures: Int = 0,
    statusReason: String? = null,
    reminderText: String? = null
) = Watch(
    id = id,
    userId = userId,
    title = "Price watch",
    condition = "Check whether the price crossed 100",
    mode = mode,
    reminderText = reminderText,
    schedule = schedule,
    untilAt = untilAt,
    nextDueAt = nextDueAt,
    status = status,
    pendingAction = pendingAction,
    version = version,
    extensionCount = extensionCount,
    tags = tags,
    completeOnMet = completeOnMet,
    notifyPolicy = notifyPolicy,
    notifyTags = notifyTags,
    sourceAgentId = "default-agent",
    modelConfigId = "model-1",
    projectId = null,
    projectPath = null,
    projectKind = ProjectKind.USER,
    owners = setOf(userId),
    originSessionId = "origin-session",
    runSessionId = "run-session",
    allowedTools = listOf("webfetch"),
    sourceHints = null,
    lastTaskId = null,
    lastRunAt = null,
    lastFinding = lastFinding,
    consecutiveFailures = consecutiveFailures,
    lastError = null,
    statusReason = statusReason,
    createdAt = 0,
    updatedAt = 0
)

internal fun watchTask(
    watch: Watch,
    kind: WatchRunKind = WatchRunKind.CHECK,
    scheduledFor: Instant = watch.nextDueAt,
    id: String = "task-1"
) = AsyncTask(
    id = id,
    userId = watch.userId,
    type = AsyncTaskType.WATCH,
    trigger = TaskTrigger.SCHEDULE,
    title = "Watch · ${watch.title}",
    sessionId = watch.runSessionId,
    payload = mapOf(
        WatchTaskPayload.KEY_WATCH_ID to watch.id,
        WatchTaskPayload.KEY_KIND to kind.name,
        WatchTaskPayload.KEY_SCHEDULED_FOR to scheduledFor.toEpochMilli(),
        WatchTaskPayload.KEY_DEFINITION_VERSION to watch.version
    )
)

/** A watch store that records what the handler tried to write and can lose a guard on purpose. */
internal class RecordingWatchStore(var current: Watch) : WatchStore {

    /** When set, the second and later reads return this row — an edit landing mid-run. */
    var afterFirstRead: Watch? = null
    private var reads = 0

    var applyFindingCalls = 0
    var appliedStatus: WatchStatus? = null
    var appliedFinding: WatchFinding? = null
    var appliedNextDue: Long? = null
    var appliedPending: WatchPendingAction? = null
    var loseApplyFindingGuard = false

    var failureCalls = 0
    var failureStatus: WatchStatus? = null
    var failureCount: Int? = null
    var failureNextDue: Long? = null
    var failureError: String? = null

    var reviewCalls = 0
    var reviewStatus: WatchStatus? = null
    var reviewPending: WatchPendingAction? = null
    var reviewUntil: Long? = null
    var reviewAnchor: Long? = null
    var reviewNextDue: Long? = null
    var reviewExtensions: Int? = null
    var reviewReason: String? = null
    var loseReviewGuard = false

    val runStartedTaskIds = mutableListOf<String>()
    val slotClaims = mutableListOf<Pair<AsyncTask, Int>>()
    var loseRunSlotGuard = false
    var runSlotFailure: Throwable? = null
    val tasks = linkedMapOf<String, AsyncTask>()
    private var lastTaskId: String? = null

    override suspend fun create(watch: Watch): Watch {
        current = watch
        return watch
    }

    override suspend fun findById(id: String): Watch? {
        reads++
        return if (reads > 1) afterFirstRead ?: current else current
    }

    override suspend fun listByUser(userId: String, statuses: Set<WatchStatus>?, limit: Int): List<Watch> =
        listOf(current)

    override suspend fun countActive(userId: String): Int = 1

    override suspend fun listUpcoming(
        horizon: Instant,
        cursor: WatchScheduleCursor?,
        limit: Int,
        userId: String?
    ): List<WatchScheduleEntry> = listOfNotNull(findSchedule(current.id)).filter {
        it.nextDueAt <= horizon && (userId == null || it.userId == userId) &&
            (cursor == null || it.nextDueAt > cursor.nextDueAt ||
                (it.nextDueAt == cursor.nextDueAt && it.watchId > cursor.watchId))
    }.take(limit)

    override suspend fun findSchedule(watchId: String): WatchScheduleEntry? =
        current.takeIf { it.id == watchId && it.status == WatchStatus.ACTIVE }?.let {
            WatchScheduleEntry(it.id, it.userId, it.version, it.nextDueAt, it.pendingAction)
        }

    override suspend fun enqueueScheduled(
        expected: WatchScheduleEntry,
        now: Instant,
        properties: WatchProperties
    ): WatchEnqueueResult {
        if (findSchedule(expected.watchId) != expected || expected.nextDueAt > now) return WatchEnqueueResult.Stale
        listInFlight(expected.userId, expected.watchId).firstOrNull()?.let { return WatchEnqueueResult.Busy(it.id) }
        val decision = WatchClaimPolicy.decide(current, now, properties) ?: return WatchEnqueueResult.Stale
        val task = watchTask(current, decision.kind, current.nextDueAt, "task-${tasks.size + 1}")
            .copy(timeoutMs = properties.runTimeoutMs)
        tasks[task.id] = task
        current = current.copy(nextDueAt = decision.newNextDueAt, pendingAction = decision.pendingAction)
        return WatchEnqueueResult.Enqueued(task)
    }

    override suspend fun enqueueManual(
        userId: String,
        watchId: String,
        now: Instant,
        properties: WatchProperties,
        trigger: TaskTrigger
    ): WatchEnqueueResult {
        if (current.userId != userId || findSchedule(watchId) == null ||
            current.pendingAction != WatchPendingAction.CHECK
        ) return WatchEnqueueResult.Stale
        listInFlight(userId, watchId).firstOrNull()?.let { return WatchEnqueueResult.Busy(it.id) }
        val task = watchTask(current, scheduledFor = now, id = "task-${tasks.size + 1}")
            .copy(trigger = trigger, timeoutMs = properties.runTimeoutMs)
        tasks[task.id] = task
        return WatchEnqueueResult.Enqueued(task)
    }

    override suspend fun retryRun(
        userId: String,
        watchId: String,
        taskId: String,
        expectedRetryCount: Int,
        now: Instant
    ): WatchEnqueueResult {
        val task = tasks[taskId] ?: return WatchEnqueueResult.Stale
        if (current.userId != userId || findSchedule(watchId) == null ||
            task.userId != userId || WatchTaskPayload.watchIdOf(task) != watchId ||
            !task.status.isTerminal || task.retryCount != expectedRetryCount
        ) return WatchEnqueueResult.Stale
        listInFlight(userId, watchId).firstOrNull()?.let { return WatchEnqueueResult.Busy(it.id) }
        val reset = task.copy(
            status = AsyncTaskStatus.PENDING, retryCount = expectedRetryCount + 1,
            retriedAt = now.toEpochMilli(), error = null, result = null, startedAt = null, completedAt = null
        )
        tasks[taskId] = reset
        return WatchEnqueueResult.Enqueued(reset)
    }

    override suspend fun listInFlight(userId: String, watchId: String): List<AsyncTask> =
        tasks.values.filter { it.userId == userId && WatchTaskPayload.watchIdOf(it) == watchId && !it.status.isTerminal }

    override suspend fun claimRunSlot(task: AsyncTask, expectedVersion: Int, at: Instant): Boolean {
        slotClaims += task to expectedVersion
        runSlotFailure?.let { throw it }
        if (loseRunSlotGuard || current.version != expectedVersion || current.status != WatchStatus.ACTIVE) return false
        runStartedTaskIds += task.id
        lastTaskId = task.id
        return true
    }

    override suspend fun applyFinding(
        id: String,
        expectedVersion: Int,
        expectedStatus: WatchStatus,
        status: WatchStatus,
        pendingAction: WatchPendingAction,
        nextDueAt: Long,
        finding: WatchFinding,
        taskId: String,
        at: Long
    ): Watch? {
        applyFindingCalls++
        if (loseApplyFindingGuard) return null
        if (lastTaskId != taskId) return null
        appliedStatus = status
        appliedFinding = finding
        appliedNextDue = nextDueAt
        appliedPending = pendingAction
        current = current.copy(
            status = status,
            lastFinding = finding,
            lastTaskId = taskId,
            lastRunAt = at,
            consecutiveFailures = 0
        )
        return current
    }

    override suspend fun recordFailure(
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
    ): Watch? {
        failureCalls++
        if (taskId != null && lastTaskId != taskId) return null
        failureStatus = status
        failureCount = consecutiveFailures
        failureNextDue = nextDueAt
        failureError = error
        current = current.copy(
            status = status,
            consecutiveFailures = consecutiveFailures,
            lastError = error,
            nextDueAt = Instant.ofEpochMilli(nextDueAt)
        )
        return current
    }

    override suspend fun applyExpiryReview(
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
    ): Watch? {
        reviewCalls++
        if (loseReviewGuard) return null
        if (lastTaskId != taskId) return null
        reviewStatus = status
        reviewPending = pendingAction
        reviewUntil = untilAt
        reviewAnchor = anchorAt
        reviewNextDue = nextDueAt
        reviewExtensions = extensionCount
        reviewReason = statusReason
        current = current.copy(
            status = status,
            pendingAction = pendingAction,
            untilAt = untilAt?.let(Instant::ofEpochMilli),
            schedule = current.schedule.copy(anchorAt = anchorAt?.let(Instant::ofEpochMilli)),
            nextDueAt = Instant.ofEpochMilli(nextDueAt),
            extensionCount = extensionCount,
            statusReason = statusReason
        )
        return current
    }

    override suspend fun updateDefinition(
        watch: Watch,
        expectedVersion: Int,
        expectedStatus: WatchStatus
    ): Watch? {
        if (current.version != expectedVersion || current.status != expectedStatus) return null
        current = watch
        return watch
    }

    override suspend fun setStatus(
        id: String,
        status: WatchStatus,
        statusReason: String?,
        pendingAction: WatchPendingAction?,
        nextDueAt: Long?,
        expectedStatus: WatchStatus?,
        clearFailures: Boolean
    ): Watch? {
        if (expectedStatus != null && current.status != expectedStatus) return null
        current = current.copy(
            status = status,
            consecutiveFailures = if (clearFailures) 0 else current.consecutiveFailures,
            lastError = if (clearFailures) null else current.lastError
        )
        return current
    }

    override suspend fun delete(id: String): Boolean = true
}

/** One-shot model answer plumbing for a handler run; the model itself is never called. */
internal class WatchModelStub(
    private val answer: String = "",
    private val failure: Throwable? = null
) {
    val sessionManager: SessionManager = mockk(relaxed = true)
    val agentService: AgentService = mockk()
    val modelConfigStore: ModelProviderConfigStore = mockk()
    private val factory: ChatModelFactory = mockk()
    private val session: ChatSession = mockk(relaxed = true)

    init {
        val config = ModelProviderConfig(
            id = "model-1",
            name = "Test",
            protocol = ModelProviderInfo.Protocol.OPENAI,
            isCustom = false,
            modelId = "gpt-test"
        )
        coEvery { modelConfigStore.getConfig(eq("model-1"), any<Collection<String>>()) } returns config
        every { agentService.chatModelFactories } returns listOf(factory)
        every { factory.supports(any()) } returns true
        every { session.agentContext } returns AgentContext(agentId = "watch-runner", sessionId = "run-session")
        every { session.lastEndReason } returns "normal"
        if (failure != null) {
            every { session.promptWithHistory(any()) } throws failure
        } else {
            every { session.promptWithHistory(any()) } returns EventStream.create<AgentEvent, List<AssistantMessage>> {
                end(listOf(AssistantMessage(content = listOf(TextContent(answer)))))
            }
        }
        coEvery { sessionManager.getOrCreateSession(any(), any(), any()) } returns session
    }
}

/** Collects the notices a run publishes, in order. */
internal class RecordingNotifier : WatchNotifier {
    val notices = mutableListOf<WatchNotice>()

    override suspend fun publish(notice: WatchNotice) {
        notices += notice
    }
}
