package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Request body for creating a new project.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class CreateProjectRequest(
    val name: String,
    val path: String,
    val description: String? = null
)