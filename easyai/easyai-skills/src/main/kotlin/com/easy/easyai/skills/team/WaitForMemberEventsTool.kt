package com.easy.easyai.skills.team

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.team.TeamEventDrain
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.core.team.TeamMemberEvent
import com.easy.easyai.core.team.TeamRoundRecord
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

/**
 * Team tool: block until member events arrive, then return them as a batch.
 *
 * This is the leader's "event loop checkpoint":
 * 1. If no members are running and no queued events → return status summary immediately
 * 2. Wait for the first event (with timeout)
 * 3. Debounce-drain additional events that arrive within the window
 * 4. Format the event batch + overall team status for the leader
 */
class WaitForMemberEventsTool(
    metadata: ToolMetadata,
    private val state: TeamCoordinationState,
    private val executionStore: TeamExecutionStore? = null,
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        /** Maximum seconds to wait for the first event. Default 300 (5 minutes). */
        val timeoutSeconds: Int = 300,
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val params = try {
            if (args.isEmpty()) Parameters()
            else SharedObjectMapper.instance.convertValue(args, Parameters::class.java)
        } catch (_: Exception) {
            Parameters()
        }

        // 1. No running members → check for queued events, otherwise return status
        if (state.activeMemberCount() == 0) {
            val queued = state.eventChannel.tryReceive()
            val queuedEvent = queued.getOrNull()
            if (queuedEvent == null) {
                return ToolResult(
                    content = listOf(TextContent(buildNoActiveMembersResponse()))
                )
            }
            // Process the queued event along with any others
            val events = TeamEventDrain.drain(state.eventChannel, queuedEvent)
            return buildEventBatchResult(events)
        }

        // 2. Wait for the first event with timeout
        val timeoutMs = params.timeoutSeconds.coerceIn(10, 1800) * 1000L
        val first = withTimeoutOrNull(timeoutMs) {
            state.eventChannel.receive()
        }

        if (first == null) {
            logger.info("Team session {}: wait_for_member_events timed out after {}s",
                state.sessionId, params.timeoutSeconds)
            return ToolResult(
                content = listOf(TextContent(
                    "Timeout: no member events received within ${params.timeoutSeconds}s.\n\n" +
                    buildStatusSummary()
                ))
            )
        }

        // 3. Drain additional events (debounce aggregation)
        val events = TeamEventDrain.drain(state.eventChannel, first)
        logger.info("Team session {}: received {} member event(s) in batch", state.sessionId, events.size)

        // 4. Format and return
        return buildEventBatchResult(events)
    }

    private suspend fun buildEventBatchResult(events: List<TeamMemberEvent>): ToolResult {
        val sb = StringBuilder()
        sb.appendLine("## Member Events (${events.size})")
        sb.appendLine()

        for (event in events) {
            val exec = event.execution
            when (event) {
                is TeamMemberEvent.Completed -> {
                    sb.appendLine("[COMPLETED] ${event.memberId} (round ${exec.round})")
                    sb.appendLine(exec.summary?.let { truncate(it, 3000) } ?: "(no summary)")
                    sb.appendLine()
                }
                is TeamMemberEvent.Blocked -> {
                    sb.appendLine("[BLOCKED] ${event.memberId} (round ${exec.round})")
                    sb.appendLine("Needs: ${exec.escalationReason ?: "unknown"}")
                    val blocked = state.blockedMembers[event.memberId]
                    blocked?.progressAtBlock?.let { sb.appendLine("Progress so far: $it") }
                    sb.appendLine("→ Use resume_member(\"${event.memberId}\", resolution) to unblock, or delegate the work elsewhere.")
                    sb.appendLine()
                }
                is TeamMemberEvent.Failed -> {
                    sb.appendLine("[ERROR] ${event.memberId} (round ${exec.round})")
                    sb.appendLine("Error: ${exec.escalationReason ?: "unknown"}")
                    sb.appendLine("→ Consider re-delegating with a clearer task or assigning to another member.")
                    sb.appendLine()
                }
            }
        }

        sb.appendLine(buildStatusSummary())

        // Persist round record
        persistRound(events)

        // Advance round after each event batch
        state.currentRound.incrementAndGet()

        return ToolResult(content = listOf(TextContent(sb.toString())))
    }

    private fun buildNoActiveMembersResponse(): String = buildString {
        appendLine("No members are currently running and no pending events remain.")
        appendLine()
        append(buildStatusSummary())
        if (state.completedResults.isNotEmpty()) {
            appendLine()
            appendLine("All delegated work has been reported above. Synthesize the results into your final response.")
        }
    }

    private fun buildStatusSummary(): String = buildString {
        appendLine("## Team Status")
        val running = state.runningJobs.keys.sorted()
        val blocked = state.blockedMembers.keys.sorted()
        val completed = state.completedResults.keys.sorted()

        running.forEach { appendLine("- $it: RUNNING") }
        blocked.forEach { id ->
            val reason = state.blockedMembers[id]?.blockReason?.let { truncate(it, 200) } ?: ""
            appendLine("- $id: BLOCKED — $reason")
        }
        completed.forEach { appendLine("- $it: COMPLETED") }

        if (running.isEmpty() && blocked.isEmpty() && completed.isEmpty()) {
            appendLine("(no members have been delegated yet)")
        }
        appendLine()
        appendLine("Running: ${running.size} | Blocked: ${blocked.size} | Completed: ${completed.size}")
    }

    private fun truncate(text: String, max: Int): String =
        if (text.length > max) text.take(max) + "... [truncated]" else text

    /** Fire-and-forget round record persistence. */
    private suspend fun persistRound(events: List<TeamMemberEvent>) {
        try {
            executionStore?.saveRound(TeamRoundRecord(
                id = java.util.UUID.randomUUID().toString(),
                teamSessionId = state.sessionId,
                round = state.currentRound.get(),
                delegatedMembers = state.drainDelegated(),
                resumedMembers = state.drainResumed(),
                completedMembers = events.filterIsInstance<TeamMemberEvent.Completed>().map { it.memberId },
                blockedMembers = events.filterIsInstance<TeamMemberEvent.Blocked>().map { it.memberId },
            ))
        } catch (e: Exception) {
            logger.warn("Failed to persist team round record: {}", e.message)
        }
    }
}
