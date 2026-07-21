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
    val isDefaultTool: Boolean = true
)