package com.easy.easyai.skills.team

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.team.BlockedMemberState
import com.easy.easyai.core.team.MemberSignalTool
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.core.team.TeamMemberEvent
import com.easy.easyai.core.team.TeamMemberExecution
import com.easy.easyai.core.team.TeamMemberExecutionEntity
import com.easy.easyai.core.team.TeamMemberHistoryLoader
import com.easy.easyai.core.team.TeamMemberStatus
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Team tool: resume a blocked member with the leader's resolution (async, immediate return).
 *
 * Flow:
 * 1. Retrieve [com.easy.easyai.core.team.BlockedMemberState] from coordination state
 * 2. Load the member's conversation history from its session
 * 3. Launch background execution: history + resolution prompt → continue agent loop
 * 4. Return immediately — the completion event arrives via wait_for_member_events
 */
class ResumeMemberTool(
    metadata: ToolMetadata,
    private val state: TeamCoordinationState,
    private val agentStore: AsyncAgentStore,
    private val agentService: AgentService,
    private val contextResolver: SubAgentContextResolver?,
    private val historyLoader: TeamMemberHistoryLoader? = null,
    private val listenerFactory: ((sessionId: String, ctx: AgentContext, parentMsgId: String, parentToolCallId: String) -> MessageListener?)? = null,
    private val executionStore: TeamExecutionStore? = null,
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL

    data class Parameters(
        /** The blocked member's ID. */
        val memberId: String,
        /** The answer, guidance, or resolution to the member's blocking issue. */
        val resolution: String,
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
            SharedObjectMapper.instance.convertValue(args, Parameters::class.java)
        } catch (_: Exception) {
            return errorResult("Invalid parameters. Required: memberId (String), resolution (String)")
        }

        // 1. Retrieve blocked state (removes it — member is no longer blocked)
        //    Falls back to DB recovery after server restart (in-memory state lost)
        val blocked = state.blockedMembers.remove(params.memberId)
            ?: recoverBlockedStateFromDb(params.memberId)
            ?: run {
            val suggestions = buildString {
                if (state.blockedMembers.isNotEmpty()) {
                    append(" Blocked members: ${state.blockedMembers.keys.joinToString()}.")
                }
                if (state.runningJobs.isNotEmpty()) {
                    append(" Running members: ${state.runningJobs.keys.joinToString()}.")
                }
            }
            return errorResult(
                "Error: Member '${params.memberId}' is not in blocked state.$suggestions"
            )
        }

        // 2. Check member is not already running (race guard)
        if (state.runningJobs.containsKey(params.memberId)) {
            return errorResult("Error: Member '${params.memberId}' is already running.")
        }

        // 3. Look up member AgentDefinition
        val userId = agentContext.userId ?: "system"
        val definition = agentStore.findById(params.memberId, userId) ?: run {
            return errorResult("Error: Member agent '${params.memberId}' not found in agent store.")
        }

        // 4. Resolve member context and tools
        val (resolvedBaseContext, derivedTools) = if (contextResolver != null) {
            contextResolver.resolve(definition, agentContext)
        } else {
            agentContext.copy(
                agentId = definition.id,
                promptTemplate = definition.promptTemplate,
                subAgents = emptyList()
            ) to agentContext.tools
        }

        // 5. Load member's conversation history for continuity
        val history = try {
            historyLoader?.loadActiveMessages(blocked.sessionId) ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to load history for member '{}' session {}: {}",
                params.memberId, blocked.sessionId, e.message)
            emptyList()
        }

        // 6. Create blocked-state holder + ask_leader signal tool (member can block again)
        val blockedRef = AtomicReference<Pair<String, String>?>(null)
        val signalTool = MemberSignalTool(
            metadata = ToolMetadata(
                name = DelegateToMemberTool.ASK_LEADER_TOOL_NAME,
                description = MemberSignalTool.signalDescription("team leader"),
                permissionCategory = "team"
            ),
            onSignal = { reason, progress -> blockedRef.set(reason to progress) }
        )

        // 7. Build member context — reuse the SAME session ID for message continuity
        val round = state.currentRound.get()
        val memberContext = resolvedBaseContext.copy(
            modelConfig = agentContext.modelConfig,
            sessionId = blocked.sessionId,
            projectId = agentContext.projectId,
            projectPath = agentContext.projectPath,
            memoryAutoGeneration = agentContext.memoryAutoGeneration,
            customInstructions = DelegateToMemberTool.buildMemberInstructions(definition, blocked.originalAssignment),
            tools = derivedTools.filter { it.name !in FORBIDDEN_MEMBER_TOOLS } + signalTool,
            maxIterations = definition.maxIterations,
            parentAgentId = agentContext.agentId,
            agentRunId = toolCallId,
            teamMembers = emptyList(),
            abortSignal = agentContext.abortSignal,
        )

        val memberService = wrapServiceWithListener(
            wrapServiceWithCompletionChecks(agentService, listOf(MemberSignalCompletionCheck(blockedRef))),
            listenerFactory?.let { factory ->
                messageId?.let { parentMsgId -> factory(blocked.sessionId, memberContext, parentMsgId, toolCallId) }
            }
        )

        // 8. Build resolution prompt
        val resolutionPrompt = buildString {
            appendLine("## Leader Resolution")
            appendLine("Your blocking issue has been addressed by the team leader:")
            appendLine()
            appendLine(params.resolution)
            appendLine()
            appendLine("Please continue with your original assignment: ${blocked.originalAssignment}")
        }

        // 9. Persist: mark original record RESUMED + insert new RUNNING record
        val newExecutionId = UUID.randomUUID().toString()
        markResumed(blocked.executionId)
        state.recordResumed(params.memberId)
        persistExecution(TeamMemberExecutionEntity(
            id = newExecutionId,
            teamSessionId = state.sessionId,
            memberId = params.memberId,
            round = round,
            assignment = blocked.originalAssignment,
            status = TeamMemberStatus.RUNNING,
            memberSessionId = blocked.sessionId,
            toolCallId = toolCallId,
            startedAt = System.currentTimeMillis(),
        ))

        // 10. Launch background resume execution
        val memberId = params.memberId
        val memberSessionId = blocked.sessionId
        val job = state.scope.launch {
            try {
                val output = executeAgentWithProtection(
                    agent = Agent(memberContext, memberService),
                    prompt = resolutionPrompt,
                    timeoutMs = definition.maxIterations * 20_000L,
                    abortSignal = agentContext.abortSignal,
                    maxSummaryLength = DelegateToMemberTool.MAX_MEMBER_RESULT_LENGTH,
                    truncateLabel = "Result",
                    label = "Member '$memberId' (resumed)",
                    initialMessages = history,
                )

                state.addTokens(
                    input = output.usage.inputTokens.toLong(),
                    output = output.usage.outputTokens.toLong(),
                    cacheRead = output.usage.cacheReadTokens.toLong(),
                    cacheWrite = output.usage.cacheWriteTokens.toLong(),
                    duration = output.usage.durationMs.toLong()
                )

                val reblocked = blockedRef.get()
                val execution = TeamMemberExecution(
                    memberId = memberId,
                    round = round,
                    assignment = blocked.originalAssignment,
                    status = when {
                        reblocked != null -> TeamMemberStatus.ESCALATED
                        output.status == ExecutionStatus.COMPLETED -> TeamMemberStatus.COMPLETED
                        else -> TeamMemberStatus.ERROR
                    },
                    summary = output.summary.takeIf { output.status == ExecutionStatus.COMPLETED },
                    escalationReason = reblocked?.first ?: output.error,
                    inputTokens = output.usage.inputTokens.toLong(),
                    outputTokens = output.usage.outputTokens.toLong(),
                    memberSessionId = memberSessionId,
                    toolCallId = toolCallId,
                )

                // Persist terminal status for the resumed execution
                updateExecution(newExecutionId, execution)

                when {
                    reblocked != null -> {
                        state.blockedMembers[memberId] = BlockedMemberState(
                            memberId = memberId,
                            sessionId = memberSessionId,
                            originalAssignment = blocked.originalAssignment,
                            blockReason = reblocked.first,
                            blockedAtRound = round,
                            progressAtBlock = reblocked.second.takeIf { it.isNotBlank() },
                            executionId = newExecutionId,
                        )
                        state.eventChannel.send(TeamMemberEvent.Blocked(memberId, execution))
                    }
                    output.status == ExecutionStatus.COMPLETED -> {
                        state.completedResults[memberId] = output.summary
                        state.eventChannel.send(TeamMemberEvent.Completed(memberId, execution))
                    }
                    else -> {
                        state.eventChannel.send(TeamMemberEvent.Failed(memberId, execution))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.error("Team session {}: resumed member '{}' failed: {}", state.sessionId, memberId, e.message, e)
                val execution = TeamMemberExecution(
                    memberId = memberId,
                    round = round,
                    assignment = blocked.originalAssignment,
                    status = TeamMemberStatus.ERROR,
                    escalationReason = "Resume failed: ${e.message}",
                    memberSessionId = memberSessionId,
                    toolCallId = toolCallId,
                )
                updateExecution(newExecutionId, execution)
                state.eventChannel.send(TeamMemberEvent.Failed(memberId, execution))
            } finally {
                state.runningJobs.remove(memberId)
            }
        }
        state.runningJobs[memberId] = job

        logger.info("Team session {}: resumed member '{}' with resolution (round {}, {} history messages)",
            state.sessionId, memberId, round, history.size)

        return ToolResult(
            content = listOf(TextContent(
                "Member '$memberId' resumed with your resolution (round $round). " +
                "It continues in the background. Use wait_for_member_events() to receive completion notifications."
            )),
            details = mapOf("memberId" to memberId, "round" to round)
        )
    }

    /**
     * Recover blocked state from DB after server restart (in-memory state lost).
     * Finds the latest ESCALATED execution for the member and reconstructs [BlockedMemberState].
     */
    private suspend fun recoverBlockedStateFromDb(memberId: String): BlockedMemberState? {
        val store = executionStore ?: return null
        return try {
            val executions = store.getExecutions(state.sessionId)
            val latestEscalated = executions
                .filter { it.memberId == memberId && it.status == TeamMemberStatus.ESCALATED }
                .maxByOrNull { it.startedAt ?: 0L }
                ?: return null
            val memberSessionId = latestEscalated.memberSessionId ?: return null
            logger.info("Team session {}: recovered blocked state for member '{}' from DB", state.sessionId, memberId)
            BlockedMemberState(
                memberId = memberId,
                sessionId = memberSessionId,
                originalAssignment = latestEscalated.assignment,
                blockReason = latestEscalated.escalationReason ?: "unknown",
                blockedAtRound = latestEscalated.round,
                executionId = latestEscalated.id,
            )
        } catch (e: Exception) {
            logger.warn("Failed to recover blocked state for member '{}': {}", memberId, e.message)
            null
        }
    }

    /** Fire-and-forget persistence — DB failures must not break coordination. */
    private suspend fun persistExecution(entity: TeamMemberExecutionEntity) {
        try {
            executionStore?.saveExecution(entity)
        } catch (e: Exception) {
            logger.warn("Failed to persist team execution record: {}", e.message)
        }
    }

    private suspend fun updateExecution(id: String, execution: TeamMemberExecution) {
        try {
            executionStore?.updateExecution(
                id = id,
                status = execution.status,
                summary = execution.summary,
                escalationReason = execution.escalationReason,
                inputTokens = execution.inputTokens,
                outputTokens = execution.outputTokens,
            )
        } catch (e: Exception) {
            logger.warn("Failed to update team execution record: {}", e.message)
        }
    }

    /** Mark the original blocked execution record as RESUMED (preserves its summary/reason/tokens). */
    private suspend fun markResumed(originalExecutionId: String?) {
        if (originalExecutionId == null) return
        try {
            executionStore?.updateStatus(originalExecutionId, TeamMemberStatus.RESUMED)
        } catch (e: Exception) {
            logger.warn("Failed to mark execution {} as RESUMED: {}", originalExecutionId, e.message)
        }
    }

    companion object {
        private val FORBIDDEN_MEMBER_TOOLS = listOf(
            "task", "ask_question",
            "delegate_to_member", "wait_for_member_events", "resume_member"
        )
    }
}
