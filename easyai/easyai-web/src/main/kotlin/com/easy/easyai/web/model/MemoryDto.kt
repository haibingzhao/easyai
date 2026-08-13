package com.easy.easyai.web.model

import com.fasterxml.jackson.annotation.JsonInclude

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MemoryEntryDto(
    val name: String,
    val description: String,
    val type: String,
    val scope: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    val maturity: String? = null,
    val scenarios: List<String> = emptyList(),
    val created: String? = null,
    val updated: String? = null
)

data class CreateMemoryRequest(
    val name: String,
    val description: String,
    val type: String,
    val scope: String,
    val content: String,
    val keywords: List<String> = emptyList(),
    /** Maturity level (low/medium/high); invalid values are ignored. */
    val maturity: String? = null,
    /** Usage scenarios; blank entries are dropped. */
    val scenarios: List<String> = emptyList(),
    /** Runtime project path; required when scope is "project". */
    val projectPath: String? = null
)

/** Partial update of a memory entry's editable fields. The type/name are immutable. */
data class UpdateMemoryRequest(
    val description: String? = null,
    val content: String? = null,
    val keywords: List<String>? = null,
    val maturity: String? = null,
    val scenarios: List<String>? = null
)

data class MemoryConfigDto(
    val enabled: Boolean
)

data class UpdateMemoryConfigRequest(
    val enabled: Boolean? = null
)
