package com.easy.easyai.core.knowledge

import kotlinx.coroutines.flow.Flow

/**
 * Async knowledge article storage interface using Exposed R2DBC.
 * All operations are non-blocking and return suspend functions or Flow.
 *
 * Access control is not part of the store: callers resolve the owner buckets and ask the
 * configured [KnowledgeAccessPolicy].
 */
interface KnowledgeArticleStore {
    /**
     * Save a knowledge article. Inserts if the ID doesn't exist, updates otherwise.
     */
    suspend fun save(article: KnowledgeArticle)

    /**
     * Find an article by its knowledge key for a specific owner bucket.
     */
    suspend fun findByKey(userId: String, knowledgeKey: String): KnowledgeArticle?

    /**
     * Find an article by ID.
     */
    suspend fun findById(id: String): KnowledgeArticle?

    /**
     * Batch lookup by knowledge keys inside one owner bucket.
     */
    suspend fun findByKeys(userId: String, knowledgeKeys: Collection<String>): List<KnowledgeArticle>

    /**
     * List every article in the given owner buckets, optionally filtered by source/category.
     */
    fun findAllInBuckets(
        userIds: Collection<String>,
        source: String? = null,
        category: String? = null,
    ): Flow<KnowledgeArticle>

    /**
     * Delete an article by ID.
     */
    suspend fun delete(id: String)

    /**
     * Delete all articles for an owner bucket.
     */
    suspend fun deleteByUser(userId: String)

    /**
     * Delete all article metadata created by one login user inside an owner bucket.
     * Used by hosts when a member is removed. This is a metadata-only delete: content in
     * object storage and RAG entries are left behind. Access is revoked only when the policy
     * denies entries without metadata ([KnowledgeAccessPolicy.canReadEntry] is permissive by
     * default), so hosts that call this must override that method or clean the content/index
     * themselves.
     */
    suspend fun deleteByCreatedBy(userId: String, createdBy: String)
}
