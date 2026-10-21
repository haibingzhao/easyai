package com.easy.easyai.core.knowledge

/**
 * Knowledge article metadata stored in the database.
 * Content lives in OSS (source of truth); EasyRAG holds the derived vector index.
 */
data class KnowledgeArticle(
    val id: String,
    val userId: String,
    val knowledgeKey: String,
    val ossKey: String,
    val title: String,
    val description: String?,
    val category: String,
    val source: String,
    val relativePath: String,
    val ext: String,
    val contentHash: String?,
    val visibility: String,
    val origin: String,
    val originSessionId: String?,
    val originMessageId: String?,
    val composedFrom: String?,
    val createdAt: Long,
    val updatedAt: Long
)
