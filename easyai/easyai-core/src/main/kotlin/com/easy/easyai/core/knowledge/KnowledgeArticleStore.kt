package com.easy.easyai.core.knowledge

import kotlinx.coroutines.flow.Flow

/**
 * Async knowledge article storage interface using Exposed R2DBC.
 *
 * Access control is not part of the store: callers resolve the owner buckets and ask the
 * configured [KnowledgeAccessPolicy].
 */
interface KnowledgeArticleStore {

    /** Save a knowledge article. Inserts if the ID doesn't exist, updates otherwise. */
    suspend fun save(article: KnowledgeArticle)

    suspend fun findById(id: String): KnowledgeArticle?

    /** Batch lookup by ids inside one owner bucket. */
    suspend fun findByIds(userId: String, ids: Collection<String>): List<KnowledgeArticle>

    /** Re-upload replacement lookup: same folder + same uploaded file name. */
    suspend fun findByFolderAndFileName(userId: String, folderId: String, fileName: String): KnowledgeArticle?

    /** List every article in the given owner buckets, optionally filtered. */
    fun findAllInBuckets(
        userIds: Collection<String>,
        folderId: String? = null,
        category: String? = null,
        status: ArticleStatus? = null,
    ): Flow<KnowledgeArticle>

    suspend fun delete(id: String)

    suspend fun deleteByUser(userId: String)

    /**
     * Delete all article metadata created by one login user inside one owner bucket.
     * This is a metadata-only delete: content in object storage and RAG entries are left behind.
     */
    suspend fun deleteByCreatedBy(userId: String, createdBy: String)
}
