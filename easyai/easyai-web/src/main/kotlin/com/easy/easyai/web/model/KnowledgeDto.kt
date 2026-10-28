package com.easy.easyai.web.model

/**
 * DTO for knowledge article list items and single-article responses.
 *
 * [id] is the stable article identity (also the RAG/object-storage key); [folderPath] is derived
 * from the folder tree and only used for display.
 */
data class KnowledgeArticleDto(
    val id: String,
    val folderId: String,
    val folderPath: String,
    val ownerId: String,
    val fileName: String?,
    val title: String,
    val description: String?,
    val category: String,
    val ext: String,
    val origin: String,
    /** `draft` or `published`. */
    val status: String,
    /** Whether the assistant may retrieve this article. */
    val ragEnabled: Boolean,
    /** Published content saved but the index write failed; re-publish/index to repair. */
    val indexPending: Boolean = false,
    val canWrite: Boolean,
    val canManage: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** DTO for a knowledge folder node; [parentId] null marks a tree root. */
data class KnowledgeFolderDto(
    val id: String,
    val parentId: String?,
    val name: String,
    val ownerId: String,
    val isDefault: Boolean,
    /** True when new content (upload, compose, create) may target this folder. */
    val writable: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)

/** DTO for per-file upload result. */
data class UploadResultDto(
    val relativePath: String,
    val success: Boolean,
    val id: String? = null,
    val reason: String? = null,
    val indexPending: Boolean = false
)

/** Response for the upload endpoint. */
data class UploadResponseDto(
    val results: List<UploadResultDto>,
    val totalFiles: Int,
    val successCount: Int,
    val failedCount: Int
)

/** Request body for saving an article from text or chat. */
data class SaveArticleRequestDto(
    val title: String,
    val content: String,
    val folderId: String? = null,
    val category: String = "other",
    val origin: String = "chat_save",
    val originSessionId: String? = null,
    val originMessageId: String? = null,
    val composedFrom: String? = null
)

/** Request body for updating article content. */
data class UpdateArticleRequestDto(
    val id: String,
    val content: String,
    val title: String? = null,
    val category: String? = null
)

/** Request body for publishing (or re-indexing) an article. */
data class PublishArticleRequestDto(
    val id: String,
    val category: String? = null,
    val title: String? = null
)

/** Request body for toggling retrieval exposure of a published article. */
data class SetArticleRagRequestDto(
    val id: String,
    val enabled: Boolean
)

/** Request body for moving an article to another folder. */
data class MoveArticleRequestDto(
    val id: String,
    val folderId: String
)

/** Request body for creating a folder; null [parentId] creates a root. */
data class CreateFolderRequestDto(
    val parentId: String? = null,
    val name: String
)

/** Request body for renaming a folder (name only; paths are derived). */
data class RenameFolderRequestDto(
    val name: String
)

/** Chunk counts for uploaded ids that finished indexing. */
data class IndexStatusDto(
    val counts: Map<String, Int>
)

/** Request body for AI rewrite operations. */
data class AiRewriteRequestDto(
    val id: String,
    val content: String? = null,
    val instruction: String,
    val modelConfigId: String
)

/** Request body for AI selection modification. */
data class AiModifySelectionRequestDto(
    val id: String,
    val content: String? = null,
    val selectedText: String,
    val instruction: String,
    val modelConfigId: String
)

/** Request body for AI article composition from multiple materials. */
data class AiComposeRequestDto(
    val sourceIds: List<String>,
    val guidance: String,
    val style: String = "",
    val modelConfigId: String
)

/** Response for AI operations. */
data class AiResultDto(
    val content: String
)
