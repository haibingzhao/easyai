package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * DTO for permission rule data transfer.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class PermissionRuleDto(
    /** Permission type, e.g., "tool.execute.shell" */
    val permission: String,
    /** Pattern to match, e.g., "*" or specific command */
    val pattern: String,
    /** Action: ALLOW, ASK, or DENY */
    val action: String
)

/**
 * Request body for saving all permission rules (full replacement).
 */
data class SaveRulesRequest(
    val rules: List<PermissionRuleDto>
)

/**
 * Request body for adding a single permission rule.
 */
data class AddRuleRequest(
    val permission: String,
    val pattern: String,
    val action: String
)

/**
 * Aggregated tool permission info for frontend display.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ToolPermissionInfoDto(
    val name: String,
    val description: String,
    val category: String?,
    val rules: List<PermissionRuleDto>
)

/**
 * Permission settings DTO for the AutoApprovePanel.
 * Contains both the project path and all boolean/list settings.
 */
data class PermissionSettingsDto(
    val projectPath: String,
    val readFileProject: Boolean,
    val readFileAll: Boolean,
    val writeFileProject: Boolean,
    val writeFileAll: Boolean,
    val executeSafeCommands: Boolean,
    val executeAllCommands: Boolean,
    val useBrowser: Boolean,
    val useMcp: Boolean,
    val readOtherPaths: List<String>,
    val writeOtherPaths: List<String>,
    val otherCommands: List<String>
)

/**
 * Request body for updating a single permission setting.
 */
data class UpdateSettingRequest(
    val key: String,
    val value: Any
)

/**
 * File tree node for the project structure browser.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class FileNodeDto(
    val name: String,
    val path: String,
    val type: String,
    val children: List<FileNodeDto>? = null
)
