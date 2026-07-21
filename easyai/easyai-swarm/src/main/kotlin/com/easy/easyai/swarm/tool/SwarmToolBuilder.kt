package com.easy.easyai.swarm.tool

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.swarm.preset.SwarmPresetStore
import com.easy.easyai.swarm.runtime.SwarmRuntime
import com.easy.easyai.swarm.store.SwarmRunStore

/**
 * ToolBuilder for [SwarmTool].
 *
 * Registered as a Spring Bean via autoconfigure. Returns null (tool not available)
 * when the SwarmRuntime is not configured (swarm feature disabled).
 */
class SwarmToolBuilder(
    private val runtime: SwarmRuntime,
    private val presetStore: SwarmPresetStore,
    private val store: SwarmRunStore? = null
) : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "run_swarm",
        description = SwarmTool.DESCRIPTION,
        permissionCategory = "swarm",
        isDefaultTool = false
    )
    override val mainAgentOnly = true

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        return SwarmTool(metadata, runtime, presetStore, store)
    }
}
