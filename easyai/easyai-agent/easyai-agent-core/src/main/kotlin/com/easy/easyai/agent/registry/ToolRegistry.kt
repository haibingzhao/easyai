package com.easy.easyai.agent.registry

import com.easy.easyai.agent.api.model.ToolInfo
import com.easy.easyai.core.tool.ToolFactory
import org.springframework.stereotype.Service

/**
 * Registry for available tools. Provides metadata about tools that can be
 * assigned to agents.
 */
interface ToolRegistry {
    /**
     * Returns all available tools.
     */
    fun getAllTools(): List<ToolInfo>

    /**
     * Returns tools by name.
     */
    fun getToolByName(name: String): ToolInfo?
}

/**
 * Default implementation backed by [ToolFactory] builders (metadata only, no Tool instances created).
 */
@Service
class DefaultToolRegistry(private val toolFactory: ToolFactory) : ToolRegistry {

    override fun getAllTools(): List<ToolInfo> {
        return toolFactory.getBuilders().map { builder ->
            ToolInfo(
                name = builder.name,
                description = builder.description,
                permissionCategory = builder.permissionCategory,
                uiRenderer = builder.uiRenderer,
                isDefaultTool = builder.isDefaultTool
            )
        }
    }

    override fun getToolByName(name: String): ToolInfo? = getAllTools().find { it.name == name }
}