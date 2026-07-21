package com.easy.easyai.api.model

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Model provider information.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class ModelProviderInfo(
    val id: String,
    val name: String,
    val protocol: Protocol,
    val isCustom: Boolean,
    val models: List<ModelInfo>,
    val description: String? = null
) {
    enum class Protocol {
        OPENAI, ANTHROPIC
    }
}