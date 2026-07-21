package com.easy.easyai.tools

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolFactory
import org.slf4j.LoggerFactory

/**
 * Spring-based [ToolFactory] that discovers all [ToolBuilder] beans via DI.
 * 
 * Extensible approach:
 * - All tool metadata is available via [getBuilders()] without creating instances
 * - Tool creation filters builders by allowed names before building
 * - New tools only need a ToolBuilder @Component - no factory changes needed
 */
class SpringToolFactory(
    private val builders: List<ToolBuilder>
) : ToolFactory {

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        logger.info("SpringToolFactory initialized with {} tool builders: {}",
            builders.size, builders.joinToString { it.name })
    }

    override fun getBuilders(): List<ToolBuilder> = builders

    override fun createTools(
        context: AgentContext,
        agentService: AgentService,
        allowedToolNames: List<String>
    ): List<ToolDefinition> {
        val filteredBuilders = if (allowedToolNames.isEmpty()) {
            builders
        } else {
            builders.filter { it.name in allowedToolNames }
        }

        return filteredBuilders.mapNotNull { builder ->
            try {
                builder.build(context, agentService)
            } catch (e: Exception) {
                logger.error(
                    "Failed to build tool '{}' from builder {}: {}",
                    builder.name, builder.javaClass.simpleName, e.message, e
                )
                null
            }
        }
    }
}
