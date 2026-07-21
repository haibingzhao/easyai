package com.easy.easyai.web.service.configgen

import com.easy.easyai.core.agent.AgentCompletionCheck
import com.easy.easyai.core.agent.AgentEventListener
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.WaitForUserListener
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.memory.MemoryStore

/**
 * Delegating [AgentService] that disables all persistence and observability.
 *
 * Used by the Config Generator Agent to ensure no DB writes occur during
 * AI-powered configuration generation (dry-run mode).
 *
 * @param delegate The real AgentService to delegate non-persistence operations to.
 */
class DryRunAgentService(
    private val delegate: AgentService
) : AgentService by delegate {

    /** Disable message persistence — no messages written to DB. */
    override val messageListener: MessageListener? = null

    /** Disable observability events — no tracing/metrics. */
    override val eventListeners: List<AgentEventListener> = emptyList()

    /** Disable completion checks — loop ends naturally when LLM stops. */
    override val completionChecks: List<AgentCompletionCheck> = emptyList()

    /** Disable wait-for-user tracking — no goal pause notifications. */
    override val waitForUserListener: WaitForUserListener? = null

    /** Disable memory loading — no user memory injected into ephemeral agent prompts. */
    override val memoryStore: MemoryStore? = null
}
