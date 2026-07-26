package com.easy.easyai.skills.team

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.SubAgentContextResolver
import com.easy.easyai.core.agent.SubAgentMessageListenerFactory
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.core.team.TeamMemberHistoryLoader
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Shared base for the three team tool builders.
 *
 * Registration guards (all must pass for team tools to be available):
 * - [AgentContext.teamMembers] is non-empty (only TEAM agents have members)
 * - [AgentContext.parentAgentId] is null (recursion prevention — members cannot be leaders)
 * - [AgentContext.sessionId] is non-null (state is session-scoped)
 * - [AsyncAgentStore] is available (member definition lookup)
 *
 * The three builders share a single [TeamCoordinationState] per session via
 * [TeamCoordinationStateRegistry], since [ToolBuilder.build] returns a single tool.
 */
abstract class TeamToolBuilderBase(
    protected val agentStore: AsyncAgentStore?,
    protected val stateRegistry: TeamCoordinationStateRegistry,
    @param:Lazy protected val contextResolver: SubAgentContextResolver? = null,
    protected val listenerFactory: SubAgentMessageListenerFactory? = null,
    protected val executionStore: TeamExecutionStore? = null,
) : ToolBuilder {

    /** Team tools are session-scoped coordination tools — always included when built. */
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.team", "*", PermissionAction.ALLOW)
    )

    /**
     * Validate team tool registration guards.
     * @return The session-scoped coordination state, or null if guards fail.
     */
    protected fun resolveState(context: AgentContext): TeamCoordinationState? {
        if (context.teamMembers.isEmpty()) return null
        if (context.parentAgentId != null) return null
        val sessionId = context.sessionId ?: return null
        if (agentStore == null) return null
        return stateRegistry.getOrCreate(sessionId)
    }

    protected fun listenerLambda(): ((String, AgentContext, String, String) -> com.easy.easyai.core.event.MessageListener?)? =
        listenerFactory?.let { factory ->
            { sid: String, ctx: AgentContext, parentMsgId: String, parentToolCallId: String ->
                factory.create(sid, ctx, parentMsgId, parentToolCallId)
            }
        }
}

/**
 * Builder for [DelegateToMemberTool] — registers `delegate_to_member` for TEAM agents.
 */
@Component
class DelegateToMemberToolBuilder(
    agentStore: AsyncAgentStore?,
    stateRegistry: TeamCoordinationStateRegistry,
    @param:Lazy contextResolver: SubAgentContextResolver? = null,
    listenerFactory: SubAgentMessageListenerFactory? = null,
    executionStore: TeamExecutionStore? = null,
) : TeamToolBuilderBase(agentStore, stateRegistry, contextResolver, listenerFactory, executionStore) {

    override val metadata = ToolMetadata(
        name = "delegate_to_member",
        description = "Delegate a task to a team member agent. " +
            "The member starts immediately in the background and this tool returns right away. " +
            "Use wait_for_member_events() to receive completion/block notifications. " +
            "Available members are listed in the system prompt.",
        permissionCategory = "team",
        isDefaultTool = false,
        alwaysInclude = true,
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val state = resolveState(context) ?: return null
        // Inject concrete member IDs into the tool description so the LLM sees
        // exact valid values in the tool schema (reduces "Researcher" vs "inline:Researcher" errors)
        val memberIds = context.teamMembers.mapNotNull { it["id"] as? String }
        val dynamicMetadata = if (memberIds.isNotEmpty()) {
            metadata.copy(description = metadata.description +
                " Valid memberId values: ${memberIds.joinToString(", ")}.")
        } else metadata
        return DelegateToMemberTool(
            metadata = dynamicMetadata,
            state = state,
            agentStore = agentStore!!,
            agentService = agentService,
            contextResolver = contextResolver,
            listenerFactory = listenerLambda(),
            executionStore = executionStore,
        )
    }
}

/**
 * Builder for [WaitForMemberEventsTool] — registers `wait_for_member_events` for TEAM agents.
 */
@Component
class WaitForMemberEventsToolBuilder(
    agentStore: AsyncAgentStore?,
    stateRegistry: TeamCoordinationStateRegistry,
    @param:Lazy contextResolver: SubAgentContextResolver? = null,
    listenerFactory: SubAgentMessageListenerFactory? = null,
    executionStore: TeamExecutionStore? = null,
) : TeamToolBuilderBase(agentStore, stateRegistry, contextResolver, listenerFactory, executionStore) {

    override val metadata = ToolMetadata(
        name = "wait_for_member_events",
        description = "Block until team members produce events (COMPLETED, BLOCKED, ERROR). " +
            "Returns a batch of all events that arrived within the debounce window, " +
            "plus an overall team status summary. Call this after delegating tasks.",
        permissionCategory = "team",
        isDefaultTool = false,
        alwaysInclude = true,
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val state = resolveState(context) ?: return null
        return WaitForMemberEventsTool(
            metadata = metadata,
            state = state,
            executionStore = executionStore,
        )
    }
}

/**
 * Builder for [ResumeMemberTool] — registers `resume_member` for TEAM agents.
 */
@Component
class ResumeMemberToolBuilder(
    agentStore: AsyncAgentStore?,
    stateRegistry: TeamCoordinationStateRegistry,
    @param:Lazy contextResolver: SubAgentContextResolver? = null,
    listenerFactory: SubAgentMessageListenerFactory? = null,
    executionStore: TeamExecutionStore? = null,
    private val historyLoader: TeamMemberHistoryLoader? = null,
) : TeamToolBuilderBase(agentStore, stateRegistry, contextResolver, listenerFactory, executionStore) {

    override val metadata = ToolMetadata(
        name = "resume_member",
        description = "Resume a blocked team member with your resolution or answer. " +
            "The member reloads its conversation history, receives your resolution, " +
            "and continues working in the background. Returns immediately.",
        permissionCategory = "team",
        isDefaultTool = false,
        alwaysInclude = true,
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val state = resolveState(context) ?: return null
        return ResumeMemberTool(
            metadata = metadata,
            state = state,
            agentStore = agentStore!!,
            agentService = agentService,
            contextResolver = contextResolver,
            historyLoader = historyLoader,
            listenerFactory = listenerLambda(),
            executionStore = executionStore,
        )
    }
}
