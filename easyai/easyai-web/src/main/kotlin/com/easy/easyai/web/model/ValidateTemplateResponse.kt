package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Response body for Jinja2 template syntax validation.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ValidateTemplateResponse(
    val valid: Boolean,
    val errors: List<TemplateValidationError>? = null
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class TemplateValidationError(
    val message: String,
    val lineNumber: Int? = null,
    val startPosition: Int? = null,
    val fieldName: String? = null,
    val severity: String? = null
)
