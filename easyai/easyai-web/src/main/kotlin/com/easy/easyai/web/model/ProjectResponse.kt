package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Response DTO for project information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ProjectResponse(
    val id: String,
    val name: String,
    val path: String,
    val description: String? = null,
    val memoryAutoGeneration: Boolean = true,
    /** 'user' = user-registered project, 'temp' = system-managed per-session scratch workspace. */
    val kind: String = "user",
    val createdAt: Long,
    val updatedAt: Long
)
