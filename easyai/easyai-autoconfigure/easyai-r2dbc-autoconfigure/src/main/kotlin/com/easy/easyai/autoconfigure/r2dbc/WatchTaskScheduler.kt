package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStateChanged
import com.easy.easyai.core.task.AsyncTaskType
import com.easy.easyai.core.task.ScheduledTaskSettingChanged
import com.easy.easyai.core.watch.WatchEnqueueResult
import com.easy.easyai.core.watch.WatchProperties
import com.easy.easyai.core.watch.WatchScheduleChanged
import com.easy.easyai.core.watch.WatchScheduleCursor
import com.easy.easyai.core.watch.WatchScheduleEntry
import com.easy.easyai.core.watch.WatchStore
import com.easy.easyai.core.watch.WatchTaskPayload
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.DisposableBean
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Scheduled
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

class WatchTaskScheduler(
    private val store: WatchStore,
    private val runner: AsyncTaskRunner,
    private val host: HostIdentity,
    private val properties: WatchProperties = WatchProperties(),
    private val clock: () -> Instant = { Instant.now() },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
) : DisposableBean {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val started = AtomicBoolean(false)
    private val preloading = AtomicBoolean(false)
    private val preloadRetryScheduled = AtomicBoolean(false)
    private val slots = mutableMapOf<String, Timer>()
    private val slotLock = Any()
    private val budgets = mutableMapOf<String, Budget>()
    private val admissionPermits = Semaphore(4)

    init {
        require(properties.perUserPerMinuteLimit > 0) { "Watch per-user minute limit must be positive" }
    }

    @EventListener(ApplicationReadyEvent::class)
    fun onApplicationReady() {
        scope.launch {
            runner.awaitStartupRecovery()
            start()
        }
    }

    suspend fun start() {
        if (!properties.enabled || !started.compareAndSet(false, true)) return
        preload()
    }

    @Scheduled(fixedDelay = 1_800_000)
    fun preloadTick() {
        if (started.get()) scope.launch { preload() }
    }

    suspend fun preload() {
        if (!started.get() || !preloading.compareAndSet(false, true)) return
        try {
            loadUpcoming()
        } catch (e: Exception) {
            if (e is CancellationException) currentCoroutineContext().ensureActive()
            logger.warn("Watch preload failed: {}", e.message, e)
            retryPreload()
        } finally {
            preloading.set(false)
        }
    }

    private fun retryPreload() {
        if (!preloadRetryScheduled.compareAndSet(false, true)) return
        scope.launch {
            delay(RETRY_MS)
            preloadRetryScheduled.set(false)
            preload()
        }
    }

    private suspend fun loadUpcoming(userId: String? = null) {
        val horizon = clock().plusMillis(LOOKAHEAD_MS)
        var cursor: WatchScheduleCursor? = null
        do {
            val page = store.listUpcoming(horizon, cursor, PAGE_SIZE, userId)
            for (entry in page) {
                if (!host.owns(entry.ownerApp)) continue
                synchronized(slotLock) {
                    val existing = slots[entry.watchId]
                    if (existing == null || existing.job.isCompleted) {
                        createTimer(entry.watchId, entry.userId, entry)
                    } else if (existing.snapshot != entry) {
                        existing.wake.trySend(Unit)
                    }
                }
            }
            cursor = page.lastOrNull()?.let { WatchScheduleCursor(it.nextDueAt, it.watchId) }
        } while (page.size == PAGE_SIZE)
    }

    @EventListener
    fun onWatchChanged(event: WatchScheduleChanged) {
        if (started.get() && host.owns(event.ownerApp)) wake(event.watchId, event.userId)
    }

    @EventListener
    fun onTaskChanged(event: AsyncTaskStateChanged) {
        val task = event.task
        if (!started.get() || task.type != AsyncTaskType.WATCH || !host.owns(task.ownerApp)) return
        if (!event.removed && !task.status.isTerminal) return
        val watchId = WatchTaskPayload.watchIdOf(task) ?: return
        wake(watchId, task.userId, terminal = true)
    }

    @EventListener
    fun onSettingChanged(event: ScheduledTaskSettingChanged) {
        if (!started.get() || event.taskType != AsyncTaskType.WATCH) return
        synchronized(slotLock) {
            slots.values.filter { it.userId == event.userId }.forEach { it.wake.trySend(Unit) }
        }
        scope.launch {
            while (started.get()) {
                try {
                    loadUpcoming(event.userId)
                    return@launch
                } catch (e: Exception) {
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    logger.warn("Failed to refresh watches for {}: {}", event.userId, e.message)
                    delay(RETRY_MS)
                }
            }
        }
    }

    private fun wake(watchId: String, userId: String, terminal: Boolean = false) {
        synchronized(slotLock) {
            val slot = slots[watchId]?.takeUnless { it.job.isCompleted }
                ?: createTimer(watchId, userId, null)
            if (terminal) slot.terminalWake.set(true)
            // Sticky as well as queued: a wake racing the clock-check timeout must not be consumed
            // by the receive that the timeout is about to cancel.
            slot.signalled.set(true)
            slot.wake.trySend(Unit)
        }
    }

    private fun createTimer(watchId: String, userId: String, initial: WatchScheduleEntry?): Timer {
        val slot = Timer(watchId, userId, initial)
        slot.job = scope.launch(start = CoroutineStart.LAZY) { drive(slot) }
        slots[watchId] = slot
        slot.job.invokeOnCompletion {
            synchronized(slotLock) {
                if (slots[watchId] === slot) {
                    slots.remove(watchId)
                    // A change arriving while this timer retires must not wait for the next preload.
                    if (started.get() && scope.isActive &&
                        (slot.signalled.getAndSet(false) || slot.wake.tryReceive().isSuccess)
                    ) {
                        createTimer(watchId, userId, null)
                    }
                }
            }
        }
        slot.job.start()
        return slot
    }

    private suspend fun drive(slot: Timer) {
        var refresh = slot.snapshot == null
        var retryAt = 0L
        var reason = RetryReason.NONE
        while (started.get()) {
            currentCoroutineContext().ensureActive()
            if (slot.signalled.getAndSet(false) || slot.wake.tryReceive().isSuccess) refresh = true
            val now = clock().toEpochMilli()
            if (refresh && (reason != RetryReason.ERROR || now >= retryAt)) {
                try {
                    val current = store.findSchedule(slot.watchId)
                    if (current == null || !host.owns(current.ownerApp) || current.nextDueAt.toEpochMilli() > now + LOOKAHEAD_MS) return
                    val changed = slot.snapshot != current
                    val terminal = slot.terminalWake.getAndSet(false)
                    slot.snapshot = current
                    if (changed || (terminal && reason == RetryReason.BUSY)) {
                        retryAt = 0
                        reason = RetryReason.NONE
                    }
                    refresh = false
                } catch (e: Exception) {
                    if (e is CancellationException) currentCoroutineContext().ensureActive()
                    logger.warn("Failed to refresh watch {}: {}", slot.watchId, e.message)
                    retryAt = clock().toEpochMilli() + RETRY_MS
                    reason = RetryReason.ERROR
                }
            }
            val snapshot = slot.snapshot
            val deadline = maxOf(snapshot?.nextDueAt?.toEpochMilli() ?: 0, retryAt)
            val remaining = deadline - clock().toEpochMilli()
            if (remaining > 0) {
                val signalled = withTimeoutOrNull(minOf(remaining, CLOCK_CHECK_MS)) {
                    slot.wake.receive()
                    true
                } ?: false
                if (signalled) refresh = true
                continue
            }
            if (refresh) continue
            if (snapshot == null) return
            try {
                when (val outcome = admit(snapshot)) {
                    is Admission.Accepted -> {
                        reason = RetryReason.NONE
                        retryAt = 0
                        refresh = true
                    }
                    is Admission.Busy -> {
                        reason = RetryReason.BUSY
                        retryAt = clock().toEpochMilli() + RETRY_MS
                    }
                    is Admission.Deferred -> {
                        reason = RetryReason.LIMIT
                        retryAt = outcome.retryAt
                    }
                    Admission.Stale -> {
                        reason = RetryReason.STALE
                        retryAt = clock().toEpochMilli() + RETRY_MS
                        refresh = true
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) currentCoroutineContext().ensureActive()
                logger.warn("Failed to enqueue watch {}: {}", slot.watchId, e.message, e)
                reason = RetryReason.ERROR
                retryAt = clock().toEpochMilli() + RETRY_MS
                refresh = true
            }
        }
    }

    private suspend fun admit(entry: WatchScheduleEntry): Admission = admissionPermits.withPermit {
        val now = clock()
        val minute = Math.floorDiv(now.toEpochMilli(), MINUTE_MS)
        val reserved = synchronized(budgets) {
            val budget = budgets.getOrPut(entry.userId) { Budget(minute, 0) }
            if (budget.minute != minute) {
                budget.minute = minute
                budget.used = 0
            }
            if (budget.used >= properties.perUserPerMinuteLimit) false else {
                budget.used++
                true
            }
        }
        if (!reserved) return@withPermit Admission.Deferred((minute + 1) * MINUTE_MS)
        var committed = false
        try {
            withContext(NonCancellable) {
                when (val result = store.enqueueScheduled(entry, now, properties)) {
                    is WatchEnqueueResult.Enqueued -> {
                        committed = true
                        runner.dispatch(result.task)
                        Admission.Accepted
                    }
                    is WatchEnqueueResult.Busy -> Admission.Busy
                    WatchEnqueueResult.Stale -> Admission.Stale
                }
            }
        } finally {
            if (!committed) synchronized(budgets) {
                budgets[entry.userId]?.takeIf { it.minute == minute }?.let { it.used-- }
            }
        }
    }

    override fun destroy() {
        started.set(false)
        scope.cancel()
        synchronized(slotLock) { slots.clear() }
        synchronized(budgets) { budgets.clear() }
    }

    private class Timer(val watchId: String, val userId: String, initial: WatchScheduleEntry?) {
        @Volatile var snapshot: WatchScheduleEntry? = initial
        val wake = Channel<Unit>(Channel.CONFLATED)
        val signalled = AtomicBoolean(false)
        val terminalWake = AtomicBoolean(false)
        lateinit var job: Job
    }

    private class Budget(var minute: Long, var used: Int)
    private enum class RetryReason { NONE, BUSY, LIMIT, ERROR, STALE }
    private sealed interface Admission {
        data object Accepted : Admission
        data object Busy : Admission
        data object Stale : Admission
        data class Deferred(val retryAt: Long) : Admission
    }

    private companion object {
        const val PAGE_SIZE = 200
        const val LOOKAHEAD_MS = 3_600_000L
        const val CLOCK_CHECK_MS = 60_000L
        const val RETRY_MS = 60_000L
        const val MINUTE_MS = 60_000L
    }
}
