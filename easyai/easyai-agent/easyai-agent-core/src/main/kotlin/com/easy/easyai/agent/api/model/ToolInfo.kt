package com.easy.easyai.agent.api.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * DTO for available tool information returned by the API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolInfo(
    val name: String,
    val description: String,
    val permissionCategory: String = name,
    val uiRenderer: String = "generic",
    val isDefaultTool: Boolean = true,
    /**
     * Whether this tool is auto-injected by the runtime and bypasses agent-level
     * toolNames filtering (e.g. team coordination tools). Such tools should not be
     * offered for manual selection in configuration UIs: selecting them is redundant
     * when they apply, and meaningless when they don't.
     */
    val alwaysInclude: Boolean = false
)