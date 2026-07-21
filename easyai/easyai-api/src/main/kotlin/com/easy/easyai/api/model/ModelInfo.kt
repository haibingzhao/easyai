package com.easy.easyai.api.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Model information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelInfo(
    val id: String,
    val name: String,
    val isCustom: Boolean = false,
    val description: String? = null
)