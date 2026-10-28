package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskPlanContext
import com.easy.easyai.core.task.ScheduledTaskPlanner
import com.easy.easyai.core.task.ScheduledTaskSetting
import com.easy.easyai.core.task.ScheduledTaskSettingChanged
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskDraft
import com.easy.easyai.core.task.TaskTrigger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

class ScheduledTaskRunner(
    private val settingStore: ScheduledTaskSettingStore?,
    private val taskStore: AsyncTaskStore?,
    private val taskRunner: AsyncTaskRunner?,
    planners: List<ScheduledTaskPlanner>,
    private val clock: () -> Instant = { Instant.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    /** Zone cron expressions are interpreted in; hosts that promise a fixed timezone set it explicitly. */
    private val scheduleZone: ZoneId = ZoneId.systemDefault()
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val plannersByType = planners.associateBy { it.type }
    private val timers = ConcurrentHashMap<ScheduleKey, Timer>()
    private val refreshLocks = Array(64) { Mutex() }
    private val planningPermits = Semaphore(4)
    private val preloading = AtomicBoolean(false)
    private val polling = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private var lastPollAt: LocalDateTime? = null

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            taskRunner?.awaitStartupRecovery()
            start()
        }
    }

    suspend fun start() {
        if (settingStore == null || taskRunner == null || !started.compareAndSet(false, true)) return
        preload()
    }

    @Scheduled(fixedDelay = 1_800_000)
    fun preloadTick() {
        if (!started.get()) return
        scope.launch { preload() }
    }

    @Scheduled(cron = "\${easyai.tasks.scheduled.scan-cron:0 * * * * *}")
    fun tick() {
        if (!started.get() || !polling.compareAndSet(false, true)) return
        scope.launch {
            try {
                pollOnce()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Scheduled polling failed: {}", e.message, e)
            } finally {
                polling.set(false)
            }
        }
    }

    @EventListener
    fun onSettingChanged(event: ScheduledTaskSettingChanged) {
        if (!started.get() || plannersByType[event.taskType]?.usesPersistentSchedule != true) return
        scope.launch { refresh(ScheduleKey(event.userId, event.taskType, event.scheduleKey)) }
    }

    suspend fun preload() {
        val store = settingStore ?: return
        if (!preloading.compareAndSet(false, true)) return
        try {
            val horizon = clock().toEpochMilli() + LOOKAHEAD_MS
            for (planner in plannersByType.values.filter { it.usesPersistentSchedule }) {
                var afterUserId: String? = null
                var afterScheduleKey: String? = null
                do {
                    val rows = store.listForScheduling(planner.type, horizon, afterUserId, PAGE_SIZE, afterScheduleKey)
                    for (setting in rows) {
                        try {
                            refreshOnce(ScheduleKey(setting.userId, setting.taskType, setting.scheduleKey))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.warn("Failed to load schedule for {} {} '{}': {}",
                                setting.userId, planner.type.key, setting.scheduleKey, e.message)
                        }
                    }
                    afterUserId = rows.lastOrNull()?.userId
                    afterScheduleKey = rows.lastOrNull()?.scheduleKey
                } while (rows.size == PAGE_SIZE)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.error("Scheduled preload failed: {}", e.message, e)
        } finally {
            preloading.set(false)
        }
    }

    private suspend fun refresh(key: ScheduleKey) {
        while (started.get()) {
            try {
                refreshOnce(key)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn("Failed to refresh schedule for {} {} '{}': {}",
                    key.userId, key.type.key, key.scheduleKey, e.message)
                delay(RETRY_MS)
            }
        }
    }

    private suspend fun refreshOnce(key: ScheduleKey) {
        val store = settingStore ?: return
        refreshLocks[(key.hashCode() ushr 1) % refreshLocks.size].withLock {
            val setting = store.get(key.userId, key.type, key.scheduleKey)
            val planner = plannersByType[key.type] ?: return@withLock
            val current = setting?.takeIf { it.enabled }?.let { initialize(it, planner) }
            if (current == null) timers.remove(key)?.job?.cancel() else schedule(current)
        }
    }

    private suspend fun initialize(setting: ScheduledTaskSetting, planner: ScheduledTaskPlanner): ScheduledTaskSetting? {
        if (!setting.enabled || setting.scheduleZone != null) return setting
        val store = settingStore ?: return null
        val zone = scheduleZone
        val cron = resolveCron(setting, planner)
        val next = if (cron == null) null else if (setting.scheduleRevision == null) {
            clock().toEpochMilli()
        } else {
            nextFire(cron, Instant.ofEpochMilli(setting.updatedAt), zone)
        }
        store.initializeSchedule(setting, next, zone.id)
        return store.get(setting.userId, setting.taskType, setting.scheduleKey)
    }

    private fun schedule(setting: ScheduledTaskSetting) {
        val key = ScheduleKey(setting.userId, setting.taskType, setting.scheduleKey)
        val due = setting.nextFireAt
        if (!setting.enabled || due == null || due > clock().toEpochMilli() + LOOKAHEAD_MS) {
            timers.remove(key)?.job?.cancel()
            return
        }
        val previous = timers[key]
        if (previous != null && previous.revision == setting.scheduleRevision && previous.due == due) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            while (true) {
                waitUntil(due)
                try {
                    planningPermits.withPermit { fire(setting) }
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Scheduled firing failed for {} {} '{}': {}",
                        key.userId, key.type.key, key.scheduleKey, e.message, e)
                    delay(RETRY_MS)
                }
            }
        }
        val timer = Timer(setting.scheduleRevision, due, job)
        timers[key] = timer
        previous?.job?.cancel()
        job.invokeOnCompletion { cause ->
            if (timers.remove(key, timer) && started.get()) scope.launch {
                if (cause is CancellationException) delay(RETRY_MS)
                refresh(key)
            }
        }
        job.start()
    }

    private suspend fun waitUntil(due: Long) {
        while (true) {
            val remaining = due - clock().toEpochMilli()
            if (remaining <= 0) return
            // Recheck wall time after sleeps or clock changes without querying the database.
            delay(minOf(remaining, CLOCK_CHECK_MS))
        }
    }

    private suspend fun fire(expected: ScheduledTaskSetting) {
        val store = settingStore ?: return
        val runner = taskRunner ?: return
        val current = store.get(expected.userId, expected.taskType, expected.scheduleKey) ?: return
        if (!current.enabled || current.scheduleRevision != expected.scheduleRevision ||
            current.nextFireAt != expected.nextFireAt || current.scheduleZone != expected.scheduleZone
        ) return
        val planner = plannersByType[current.taskType] ?: return
        val zone = ZoneId.of(requireNotNull(current.scheduleZone))
        val now = clock()
        val context = context(current, planner, now)
        val drafts = planner.plan(context).take(planner.perUserPerTickLimit)
        val tasks = drafts.map { draft ->
            toTask(draft, current.timeoutMs).copy(
                payload = draft.payload + ("scheduledFor" to requireNotNull(current.nextFireAt))
            )
        }
        val next = resolveCron(current, planner)?.let { nextFire(it, clock(), zone) }
        val accepted = store.submitScheduled(current, next, tasks) ?: return
        accepted.forEach { runner.dispatch(it) }
    }

    suspend fun pollOnce(now: Instant = clock()) {
        val store = settingStore ?: return
        val localNow = LocalDateTime.ofInstant(now, scheduleZone)
        val windowStart = lastPollAt ?: localNow.minusSeconds(60)
        lastPollAt = localNow
        for (planner in plannersByType.values.filterNot { it.usesPersistentSchedule }) {
            for (setting in store.listEnabled(planner.type)) {
                try {
                    val next = resolveCron(setting, planner)?.next(windowStart) ?: continue
                    if (next.isAfter(localNow)) continue
                    val drafts = planner.plan(context(setting, planner, now)).take(planner.perUserPerTickLimit)
                    for (draft in drafts) {
                        try {
                            taskRunner?.submit(toTask(draft, setting.timeoutMs))
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            logger.error("Failed to submit polled task for {} {}: {}", setting.userId, planner.type.key, e.message, e)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    logger.error("Scheduled polling failed for {} {}: {}", setting.userId, planner.type.key, e.message, e)
                }
            }
        }
    }

    private suspend fun context(
        setting: ScheduledTaskSetting,
        planner: ScheduledTaskPlanner,
        now: Instant
    ): ScheduledTaskPlanContext = ScheduledTaskPlanContext(
        userId = setting.userId,
        setting = setting,
        now = now,
        pendingTasks = taskStore?.listNonTerminal(setting.userId, planner.type, 200).orEmpty()
    )

    private fun toTask(draft: TaskDraft, timeoutMs: Long?): AsyncTask = AsyncTask(
        id = UUID.randomUUID().toString(),
        userId = draft.userId,
        type = draft.type,
        trigger = TaskTrigger.SCHEDULE,
        title = draft.title,
        sessionId = draft.sessionId,
        timeoutMs = timeoutMs ?: draft.timeoutMs,
        payload = draft.payload
    )

    private fun resolveCron(setting: ScheduledTaskSetting, planner: ScheduledTaskPlanner): CronExpression? {
        val userCron = setting.cron?.takeIf { it.isNotBlank() }
        val expression = userCron ?: planner.defaultCron ?: return null
        return runCatching { CronExpression.parse(expression) }.getOrElse {
            logger.warn("Invalid cron '{}' for {} {}", expression, setting.userId, setting.taskType.key)
            if (userCron == null) null else planner.defaultCron?.let { fallback ->
                runCatching { CronExpression.parse(fallback) }.getOrNull()
            }
        }
    }

    private fun nextFire(cron: CronExpression, after: Instant, zone: ZoneId): Long? =
        cron.next(after.atZone(zone))?.toInstant()?.toEpochMilli()

    override fun destroy() {
        started.set(false)
        scope.cancel()
        timers.clear()
    }

    private data class ScheduleKey(val userId: String, val type: AsyncTaskType, val scheduleKey: String)
    private data class Timer(val revision: String?, val due: Long, val job: Job)

    private companion object {
        const val PAGE_SIZE = 200
        const val LOOKAHEAD_MS = 3_600_000L
        const val RETRY_MS = 60_000L
        const val CLOCK_CHECK_MS = 60_000L
    }
}
