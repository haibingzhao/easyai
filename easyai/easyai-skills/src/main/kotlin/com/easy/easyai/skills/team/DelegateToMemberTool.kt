package com.easy.easyai.skills.team

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.team.*
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import java.util.*
import java.util.concurrent.atomic.AtomicReference

/**
 * Team tool: delegate a task to a member agent (async launch, immediate return).
 *
 * The member runs in a background coroutine within [TeamCoordinationState.scope].
 * Upon completion/block/failure, a [TeamMemberEvent] is sent to the state's
 * eventChannel, which the leader receives via [WaitForMemberEventsTool].
 *
 * Execution model:
 * 1. Validate memberId against configured team members
 * 2. Resolve member AgentDefinition + tools (via [SubAgentContextResolver])
 * 3. Inject ask_leader ([MemberSignalTool]) + completion check into member
 * 4. Launch background execution with [executeAgentWithProtection]
 * 5. Return immediately — leader continues its coordination loop
 */
class DelegateToMemberTool(
    metadata: ToolMetadata,
    private val state: TeamCoordinationState,
    private val agentStore: AsyncAgentStore,
    private val agentService: AgentService,
    private val contextResolver: SubAgentContextResolver?,
    private val listenerFactory: ((sessionId: String, ctx: AgentContext, parentMsgId: String, parentToolCallId: String) -> MessageListener?)? = null,
    private val executionStore: TeamExecutionStore? = null,
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL

    data class Parameters(
        /** The member agent's ID (must be one of the configured team members). */
        val memberId: String,
        /** The task to delegate — a complete prompt with all necessary context. */
        val task: String,
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
            return errorResult("Invalid parameters. Required: memberId (String), task (String)")
        }

        // 1. Resolve memberId against configured team members (lenient matching:
        //    LLMs often pass the display name "Researcher" instead of the full ID "inline:Researcher")
        val memberId = resolveMemberId(params.memberId, agentContext.teamMembers)
            ?: run {
                val memberIds = agentContext.teamMembers.mapNotNull { it["id"] as? String }
                return errorResult(
                    "Error: Member '${params.memberId}' is not a configured team member. " +
                    "Available members: ${memberIds.joinToString()}"
                )
            }

        // 2. Check member is not already running
        if (state.runningJobs.containsKey(memberId)) {
            return errorResult(
                "Error: Member '$memberId' is already running. " +
                "Wait for its completion via wait_for_member_events before re-delegating."
            )
        }

        // 3. Look up member AgentDefinition (DB lookup with inline fallback) and resolve effective context
        val resolved = TeamMemberResolver.resolve(memberId, agentContext, agentStore)
            ?: return errorResult("Error: Member agent '$memberId' not found in agent store.")
        val definition = resolved.definition
        val effectiveContext = resolved.effectiveContext

        val (resolvedBaseContext, derivedTools) = contextResolver?.resolve(definition, effectiveContext)
            ?: (effectiveContext.copy(
                agentId = definition.id,
                promptTemplate = definition.promptTemplate,
                subAgents = emptyList()
            ) to effectiveContext.tools.filter { it.name !in FORBIDDEN_MEMBER_TOOLS })

        // 5. Create blocked-state holder + ask_leader signal tool
        val blockedRef = AtomicReference<Pair<String, String>?>(null)
        val signalTool = MemberSignalTool(
            metadata = ToolMetadata(
                name = ASK_LEADER_TOOL_NAME,
                description = MemberSignalTool.signalDescription("team leader"),
                permissionCategory = "team"
            ),
            onSignal = { reason, progress -> blockedRef.set(reason to progress) }
        )

        // 6. Build member context
        val memberSessionId = UUID.randomUUID().toString()
        val round = state.currentRound.get()
        val memberContext = resolvedBaseContext.copy(
            modelConfig = agentContext.modelConfig,
            sessionId = memberSessionId,
            projectId = agentContext.projectId,
            projectPath = agentContext.projectPath,
            memoryAutoGeneration = agentContext.memoryAutoGeneration,
            customInstructions = buildMemberInstructions(definition, params.task),
            tools = derivedTools.filter { it.name !in FORBIDDEN_MEMBER_TOOLS } + signalTool,
            maxIterations = definition.maxIterations,
            parentAgentId = agentContext.agentId,
            agentRunId = toolCallId,
            teamMembers = emptyList(),
            abortSignal = agentContext.abortSignal,
        )

        // 7. Wrap service with message listener for member session persistence
        val memberService = wrapServiceWithListener(
            wrapServiceWithCompletionChecks(agentService, listOf(MemberSignalCompletionCheck(blockedRef))),
            listenerFactory?.let { factory ->
                messageId?.let { parentMsgId -> factory(memberSessionId, memberContext, parentMsgId, toolCallId) }
            }
        )

        // 8. Persist RUNNING record
        val executionId = UUID.randomUUID().toString()
        state.recordDelegated(memberId)
        persistExecution(TeamMemberExecutionEntity(
            id = executionId,
            teamSessionId = state.sessionId,
            memberId = memberId,
            round = round,
            assignment = params.task,
            status = TeamMemberStatus.RUNNING,
            memberSessionId = memberSessionId,
            toolCallId = toolCallId,
            startedAt = System.currentTimeMillis(),
        ))

        // 9. Launch background execution — returns immediately
        val assignment = params.task
        val job = state.scope.launch {
            executeMember(
                memberId = memberId,
                definition = definition,
                memberContext = memberContext,
                memberService = memberService,
                assignment = assignment,
                round = round,
                toolCallId = toolCallId,
                memberSessionId = memberSessionId,
                blockedRef = blockedRef,
                abortSignal = agentContext.abortSignal,
                executionId = executionId,
            )
        }
        state.runningJobs[memberId] = job

        logger.info("Team session {}: delegated to member '{}' (round {}, session {})",
            state.sessionId, memberId, round, memberSessionId)

        return ToolResult(
            content = listOf(TextContent(
                "Member '$memberId' launched with task (round $round). " +
                "It runs in the background. Use wait_for_member_events() to receive completion notifications."
            )),
            details = mapOf("memberId" to memberId, "memberSessionId" to memberSessionId, "round" to round)
        )
    }

    /**
     * Background member execution — runs within [TeamCoordinationState.scope].
     * Sends a [TeamMemberEvent] to the event channel upon completion.
     */
    private suspend fun executeMember(
        memberId: String,
        definition: AgentDefinition,
        memberContext: AgentContext,
        memberService: AgentService,
        assignment: String,
        round: Int,
        toolCallId: String,
        memberSessionId: String,
        blockedRef: AtomicReference<Pair<String, String>?>,
        abortSignal: () -> Boolean,
        executionId: String,
    ) {
        try {
            val output = executeAgentWithProtection(
                agent = Agent(memberContext, memberService),
                prompt = assignment,
                timeoutMs = definition.maxIterations * 20_000L,
                abortSignal = abortSignal,
                onEvent = { event ->
                    // Member events are not streamed in real-time; the leader
                    // receives batched results via wait_for_member_events.
                },
                maxSummaryLength = MAX_MEMBER_RESULT_LENGTH,
                truncateLabel = "Result",
                label = "Member '$memberId'",
            )

            // Accumulate token usage
            state.addTokens(
                input = output.usage.inputTokens.toLong(),
                output = output.usage.outputTokens.toLong(),
                cacheRead = output.usage.cacheReadTokens.toLong(),
                cacheWrite = output.usage.cacheWriteTokens.toLong(),
                duration = output.usage.durationMs
            )

            val blocked = blockedRef.get()
            val execution = TeamMemberExecution(
                memberId = memberId,
                round = round,
                assignment = assignment,
                status = when {
                    blocked != null -> TeamMemberStatus.ESCALATED
                    output.status == ExecutionStatus.COMPLETED -> TeamMemberStatus.COMPLETED
                    else -> TeamMemberStatus.ERROR
                },
                summary = output.summary.takeIf { output.status == ExecutionStatus.COMPLETED },
                escalationReason = blocked?.first ?: output.error,
                inputTokens = output.usage.inputTokens.toLong(),
                outputTokens = output.usage.outputTokens.toLong(),
                memberSessionId = memberSessionId,
                toolCallId = toolCallId,
            )

            // Persist terminal status
            updateExecution(executionId, execution)

            when {
                blocked != null -> {
                    state.blockedMembers[memberId] = BlockedMemberState(
                        memberId = memberId,
                        sessionId = memberSessionId,
                        originalAssignment = assignment,
                        blockReason = blocked.first,
                        blockedAtRound = round,
                        progressAtBlock = blocked.second.takeIf { it.isNotBlank() },
                        executionId = executionId,
                    )
                    logger.info("Team session {}: member '{}' BLOCKED: {}", state.sessionId, memberId, blocked.first)
                    state.eventChannel.send(TeamMemberEvent.Blocked(memberId, execution))
                }
                output.status == ExecutionStatus.COMPLETED -> {
                    state.completedResults[memberId] = output.summary
                    logger.info("Team session {}: member '{}' COMPLETED ({} chars)",
                        state.sessionId, memberId, output.summary.length)
                    state.eventChannel.send(TeamMemberEvent.Completed(memberId, execution))
                }
                else -> {
                    logger.warn("Team session {}: member '{}' FAILED: {}", state.sessionId, memberId, output.error)
                    state.eventChannel.send(TeamMemberEvent.Failed(memberId, execution))
                }
            }
        } catch (e: CancellationException) {
            // Scope canceled (session ended) — propagate without sending events
            throw e
        } catch (e: Exception) {
            logger.error("Team session {}: member '{}' unexpected error: {}", state.sessionId, memberId, e.message, e)
            val execution = TeamMemberExecution(
                memberId = memberId,
                round = round,
                assignment = assignment,
                status = TeamMemberStatus.ERROR,
                escalationReason = "Unexpected error: ${e.message}",
                memberSessionId = memberSessionId,
                toolCallId = toolCallId,
            )
            updateExecution(executionId, execution)
            state.eventChannel.send(TeamMemberEvent.Failed(memberId, execution))
        } finally {
            state.runningJobs.remove(memberId)
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

    companion object {
        const val ASK_LEADER_TOOL_NAME = "ask_leader"
        const val MAX_MEMBER_RESULT_LENGTH = 10_000

        /**
         * Lenient member ID resolution. LLMs frequently pass the display name
         * (e.g. "Researcher") instead of the configured full ID ("inline:Researcher").
         *
         * Resolution order:
         * 1. Exact ID match
         * 2. Match by [name] field (display name)
         * 3. "inline:{input}" prefix match
         * 4. Case-insensitive ID / name match
         *
         * @return The canonical member ID, or null if unresolvable.
         */
        fun resolveMemberId(input: String, teamMembers: List<Map<String, Any?>>): String? {
            val ids = teamMembers.mapNotNull { it["id"] as? String }
            // 1. Exact match
            if (input in ids) return input
            // 2. Match by display name
            teamMembers.find { (it["name"] as? String) == input }?.let { return it["id"] as? String ?: input }
            // 3. Inline prefix
            val prefixed = "inline:$input"
            if (prefixed in ids) return prefixed
            // 4. Case-insensitive fallback
            ids.find { it.equals(input, ignoreCase = true) }?.let { return it }
            teamMembers.find { (it["name"] as? String)?.equals(input, ignoreCase = true) == true }
                ?.let { return it["id"] as? String ?: input }
            return null
        }

        /** Tools never given to team members (prevent recursion and user interaction). */
        private val FORBIDDEN_MEMBER_TOOLS = listOf(
            "task", "ask_question",
            "delegate_to_member", "wait_for_member_events", "resume_member"
        )

        /**
         * Build member instructions: role definition + final response requirements.
         * Same pattern as SubAgentTool.buildSubAgentSystemPrompt.
         */
        fun buildMemberInstructions(definition: AgentDefinition, task: String): String {
            val sb = StringBuilder()
            if (definition.promptTemplate.isNullOrBlank()) {
                if (!definition.customInstructions.isNullOrBlank()) {
                    sb.appendLine(definition.customInstructions)
                } else {
                    sb.appendLine("You are a team member agent named '${definition.name}'.")
                    if (!definition.description.isNullOrBlank()) {
                        sb.appendLine(definition.description)
                    }
                    sb.appendLine()
                    sb.appendLine("## Constraints")
                    sb.appendLine("- Focus ONLY on the task assigned by the team leader.")
                    sb.appendLine("- Do NOT attempt tasks outside the scope of your assignment.")
                    sb.appendLine("- Work autonomously with available tools.")
                }
            }
            sb.appendLine()
            sb.appendLine("## Team Member Constraints")
            sb.appendLine("- You are a team MEMBER, not the leader. You CANNOT delegate work to other agents.")
            sb.appendLine("- Tools like delegate_to_member, wait_for_member_events, resume_member, and task are NOT available to you. Never attempt to call them.")
            sb.appendLine("- Complete the assigned work YOURSELF using your own tools.")
            sb.appendLine()
            sb.appendLine("## Final Response Requirements")
            sb.appendLine("Your final message is the ONLY content reported to the team leader.")
            sb.appendLine("Intermediate work (tool calls, file reads, analysis) is NOT visible to the leader.")
            sb.appendLine("Therefore, your final response MUST be comprehensive and self-contained:")
            sb.appendLine("- List all files created, modified, or deleted with their paths")
            sb.appendLine("- Summarize key findings, decisions made, and their rationale")
            sb.appendLine("- Include relevant code snippets or command outputs if important")
            sb.appendLine("- Report any errors encountered and how they were resolved")
            sb.appendLine()
            sb.appendLine("## If You Are Blocked")
            sb.appendLine("If you cannot proceed (missing information, dependency on another member's work, etc.),")
            sb.appendLine("you MUST call the `ask_leader` tool with a clear description of what you need.")
            sb.appendLine("Do NOT just mention the problem in text — use the tool to formally signal the leader.")
            return sb.toString()
        }
    }
}
