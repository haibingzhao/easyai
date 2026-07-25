package com.easy.easyai.skills.team

import com.easy.easyai.core.team.BlockedMemberState
import com.easy.easyai.core.team.TeamMemberEvent
import com.easy.easyai.core.team.TeamTokenUsage
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Session-scoped in-memory coordination state for a Team Agent.
 *
 * Shared by the three team tools (delegate_to_member, wait_for_member_events,
 * resume_member) within the same session. Created lazily by
 * [TeamCoordinationStateRegistry] when the first team tool is built.
 *
 * Lifecycle:
 * - Created: first ToolBuilder.build() call for a TEAM agent session
 * - Destroyed: session GC (pure in-memory; DB records + message history
 *   are sufficient to rebuild state after restart — see TeamExecutionStore)
 */
class TeamCoordinationState(val sessionId: String) {

    /** Unbounded channel for member completion/block/failure events. */
    val eventChannel = Channel<TeamMemberEvent>(Channel.UNLIMITED)

    /** Background member execution jobs. Key = memberId. */
    val runningJobs = ConcurrentHashMap<String, Job>()

    /** Members that signaled blocked state via ask_leader. Key = memberId. */
    val blockedMembers = ConcurrentHashMap<String, BlockedMemberState>()

    /** Completed member results. Key = memberId, value = result summary. */
    val completedResults = ConcurrentHashMap<String, String>()

    /** Accumulated token usage across all member executions. */
    private val totalTokens = TeamTokenUsage()

    /** Lock guarding [totalTokens] (member coroutines accumulate concurrently). */
    private val tokenLock = Any()

    /** Members delegated since the last round record (drained by wait_for_member_events). */
    private val delegatedInRound = ConcurrentLinkedQueue<String>()

    /** Members resumed since the last round record (drained by wait_for_member_events). */
    private val resumedInRound = ConcurrentLinkedQueue<String>()

    /** Current coordination round (incremented on each wait_for_member_events cycle). */
    val currentRound = AtomicInteger(1)

    /**
     * Session-scoped coroutine scope for background member executions.
     * SupervisorJob ensures one member's failure does not cancel siblings.
     */
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineName("team-$sessionId")
    )

    /** Number of currently running member jobs. */
    fun activeMemberCount(): Int = runningJobs.size

    /** Thread-safe token accumulation (member coroutines run on Dispatchers.Default). */
    fun addTokens(input: Long, output: Long, cacheRead: Long = 0, cacheWrite: Long = 0, duration: Long = 0) {
        synchronized(tokenLock) {
            totalTokens.add(input, output, cacheRead, cacheWrite, duration)
        }
    }

    /** Thread-safe snapshot of accumulated token usage. */
    fun tokenSnapshot(): TeamTokenUsage.Snapshot = synchronized(tokenLock) { totalTokens.snapshot() }

    /** Record a member delegation for the current round window. */
    fun recordDelegated(memberId: String) { delegatedInRound.add(memberId) }

    /** Record a member resume for the current round window. */
    fun recordResumed(memberId: String) { resumedInRound.add(memberId) }

    /** Drain delegated member ids accumulated since the last round record. */
    fun drainDelegated(): List<String> = drainQueue(delegatedInRound)

    /** Drain resumed member ids accumulated since the last round record. */
    fun drainResumed(): List<String> = drainQueue(resumedInRound)

    private fun drainQueue(queue: ConcurrentLinkedQueue<String>): List<String> {
        val out = mutableListOf<String>()
        while (true) { out.add(queue.poll() ?: break) }
        return out
    }

    /** Cancel all running members and the coordination scope. */
    fun cancelAll() {
        runningJobs.values.forEach { it.cancel() }
        runningJobs.clear()
        scope.cancel()
    }
}

/**
 * Registry for session-scoped [TeamCoordinationState] instances.
 *
 * The three team tool builders each call [getOrCreate] with the same sessionId,
 * ensuring they share a single state instance per session (ToolBuilder.build()
 * returns a single tool, so three builders cooperate via this registry).
 *
 * Cleanup: the primary hook is [remove], invoked on session deletion. As a safety
 * net for sessions that end without an explicit close (crash, abandoned chat),
 * [getOrCreate] opportunistically evicts states that have been idle longer than
 * [IDLE_TTL_MS] and have no active members.
 */
@Component
class TeamCoordinationStateRegistry {
    private val states = ConcurrentHashMap<String, TeamCoordinationState>()
    private val lastAccess = ConcurrentHashMap<String, Long>()

    /** Get or create the coordination state for a session. */
    fun getOrCreate(sessionId: String): TeamCoordinationState {
        evictIdle()
        lastAccess[sessionId] = System.currentTimeMillis()
        return states.computeIfAbsent(sessionId) { TeamCoordinationState(it) }
    }

    /** Remove and cancel the coordination state for a session (cleanup hook). */
    fun remove(sessionId: String) {
        states.remove(sessionId)?.cancelAll()
        lastAccess.remove(sessionId)
    }

    /** Evict states idle longer than [IDLE_TTL_MS] with no active members (safety net). */
    private fun evictIdle() {
        val now = System.currentTimeMillis()
        for ((sessionId, state) in states) {
            val idleMs = now - (lastAccess[sessionId] ?: now)
            if (idleMs > IDLE_TTL_MS && state.activeMemberCount() == 0) {
                remove(sessionId)
            }
        }
    }

    companion object {
        private const val IDLE_TTL_MS = 30 * 60 * 1000L // 30 minutes
    }
}
