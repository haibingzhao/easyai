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

// ── Article editing DTOs ──────────────────────────────────────────────

/**
 * DTO for knowledge article list items (DB-backed).
 */
data class KnowledgeArticleDto(
    val id: String,
    val knowledgeKey: String,
    val title: String,
    val description: String?,
    val category: String,
    val source: String,
    val relativePath: String,
    val ext: String,
    val origin: String,
    val canWrite: Boolean,
    val canManage: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * Request body for saving an article from text or chat.
 */
data class SaveArticleRequestDto(
    val title: String,
    val content: String,
    val source: String = "chat-saved",
    val relativePath: String? = null,
    val category: String = "other",
    val origin: String = "chat_save",
    val originSessionId: String? = null,
    val originMessageId: String? = null,
    val composedFrom: String? = null
)

/**
 * Request body for updating article content.
 */
data class UpdateArticleRequestDto(
    val key: String,
    val content: String,
    val title: String? = null,
    val category: String? = null
)

/**
 * Request body for AI rewrite operations.
 */
data class AiRewriteRequestDto(
    val key: String,
    val content: String? = null,
    val instruction: String,
    val modelConfigId: String
)

/**
 * Request body for AI selection modification.
 */
data class AiModifySelectionRequestDto(
    val key: String,
    val content: String? = null,
    val selectedText: String,
    val instruction: String,
    val modelConfigId: String
)

/**
 * Request body for AI article composition from multiple materials.
 */
data class AiComposeRequestDto(
    val sourceKeys: List<String>,
    val guidance: String,
    val style: String = "",
    val modelConfigId: String
)

/**
 * Response for AI operations.
 */
data class AiResultDto(
    val content: String
)
