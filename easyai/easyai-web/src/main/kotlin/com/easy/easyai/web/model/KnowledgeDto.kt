package com.easy.easyai.web.model

/**
 * DTO for knowledge entry list items.
 */
data class KnowledgeEntryDto(
    val key: String,
    val source: String,
    val relativePath: String,
    val title: String,
    val description: String,
    val category: String,
    val ext: String,
    val contentPreview: String,
    val updatedAt: Long?,
    val chunksCount: Int?
)

/**
 * DTO for knowledge detail including relationships.
 */
data class KnowledgeDetailDto(
    val entry: KnowledgeEntryDto,
    val fullContent: String,
    val toc: List<String>,
    val parent: String?,
    val children: List<String>,
    val related: List<String>
)

/**
 * DTO for per-file upload result.
 */
data class UploadResultDto(
    val relativePath: String,
    val success: Boolean,
    val key: String? = null,
    val reason: String? = null
)

/**
 * Response for the upload endpoint.
 */
data class UploadResponseDto(
    val results: List<UploadResultDto>,
    val totalFiles: Int,
    val successCount: Int,
    val failedCount: Int
)
