package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Request body for updating an existing project.
 * All fields are optional; only provided fields are updated.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class UpdateProjectRequest(
    val name: String? = null,
    val description: String? = null,
    val memoryAutoGeneration: Boolean? = null
)
