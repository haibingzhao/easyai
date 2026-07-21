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
    val created: String? = null,
    val updated: String? = null
)

data class CreateMemoryRequest(
    val name: String,
    val description: String,
    val type: String,
    val scope: String,
    val content: String,
    val keywords: List<String> = emptyList()
)

data class MemoryConfigDto(
    val enabled: Boolean,
    val globalDir: String,
    val projectDir: String
)

data class UpdateMemoryConfigRequest(
    val enabled: Boolean? = null
)
