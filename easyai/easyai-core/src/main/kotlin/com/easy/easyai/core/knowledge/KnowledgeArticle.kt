package com.easy.easyai.core.knowledge

/**
 * Knowledge article metadata. Content lives in object storage (source of truth); EasyRAG holds the
 * derived vector index keyed by [id].
 *
 * [status] is the boundary between "a note I wrote" and "knowledge the agent can retrieve": only
 * [ArticleStatus.PUBLISHED] rows with [ragEnabled] are indexed, and a draft is visible to its
 * creator alone.
 */
data class KnowledgeArticle(
    val id: String,
    val userId: String,
    /** Login user that created the article; used by the host access policy. */
    val createdBy: String? = null,
    /** Owning folder; every article belongs to a folder. */
    val folderId: String,
    /** Upload file's leaf name, used to replace the same file on re-upload; null for hand-written articles. */
    val fileName: String? = null,
    val title: String,
    val description: String?,
    val category: String,
    val ext: String,
    val origin: String,
    val originSessionId: String? = null,
    val originMessageId: String? = null,
    val composedFrom: String? = null,
    val status: ArticleStatus = ArticleStatus.DRAFT,
    /** Whether content is exposed to the agent's retrieval index. Only meaningful when published. */
    val ragEnabled: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long
)
