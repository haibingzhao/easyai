package com.easy.easyai.skills.subagent

import com.easy.easyai.core.agent.Agent
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentRunner
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.UserMessage

/**
 * Strategy interface for executing sub-agents.
 * Enables swapping local/remote execution implementations.
 *
 * V1: Only [LocalSubAgentExecutorStrategy] is implemented.
 * V2: Can add [RemoteSubAgentExecutorStrategy] for A2A protocol support.
 */
interface SubAgentExecutorStrategy {
    /**
     * Execute a sub-agent with the given context and return the result messages.
     */
    suspend fun execute(
        agentContext: AgentContext,
        agentService: AgentService,
        prompt: String
    ): List<AssistantMessage>
}

/**
 * Local execution strategy — creates an AgentRunner and executes directly.
 * This is the default V1 implementation.
 */
class LocalSubAgentExecutorStrategy : SubAgentExecutorStrategy {

    override suspend fun execute(
        agentContext: AgentContext,
        agentService: AgentService,
        prompt: String
    ): List<AssistantMessage> {
        val runner = AgentRunner(
            agent = Agent(agentContext, agentService),
            messages = mutableListOf(),
            abortSignal = agentContext.abortSignal
        )
        return runner.prompt(listOf(UserMessage(prompt))).result()
    }
}
