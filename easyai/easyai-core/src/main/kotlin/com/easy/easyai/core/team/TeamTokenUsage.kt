package com.easy.easyai.core.team

/**
 * Mutable token counter accumulator shared by Swarm TEAM and Team Agents.
 *
 * Extracted from Swarm's internal TokenCounters to eliminate repetitive
 * field-by-field token summation across leader, member, and round result aggregation.
 *
 * Not thread-safe by design — each coordinator owns a single instance and
 * accumulates results sequentially within its event loop.
 */
class TeamTokenUsage {
    var input: Long = 0L
    var output: Long = 0L
    var cacheRead: Long = 0L
    var cacheWrite: Long = 0L
    var duration: Long = 0L

    /** Add individual token counts. */
    fun add(
        input: Long,
        output: Long,
        cacheRead: Long = 0,
        cacheWrite: Long = 0,
        duration: Long = 0,
    ) {
        this.input += input
        this.output += output
        this.cacheRead += cacheRead
        this.cacheWrite += cacheWrite
        this.duration += duration
    }

    /** Accumulate from another usage instance. */
    operator fun plusAssign(other: TeamTokenUsage) {
        input += other.input
        output += other.output
        cacheRead += other.cacheRead
        cacheWrite += other.cacheWrite
        duration += other.duration
    }

    /** Accumulate from an immutable snapshot. */
    operator fun plusAssign(s: Snapshot) {
        input += s.input
        output += s.output
        cacheRead += s.cacheRead
        cacheWrite += s.cacheWrite
        duration += s.duration
    }

    /** Capture an immutable snapshot of current counters. */
    fun snapshot() = Snapshot(input, output, cacheRead, cacheWrite, duration)

    /** Immutable point-in-time token counts. */
    data class Snapshot(
        val input: Long,
        val output: Long,
        val cacheRead: Long,
        val cacheWrite: Long,
        val duration: Long,
    )

    companion object {
        /** Create an instance pre-loaded with the given counts. */
        @JvmStatic
        fun of(input: Long, output: Long, cacheRead: Long = 0, cacheWrite: Long = 0, duration: Long = 0) =
            TeamTokenUsage().apply { add(input, output, cacheRead, cacheWrite, duration) }
    }
}
