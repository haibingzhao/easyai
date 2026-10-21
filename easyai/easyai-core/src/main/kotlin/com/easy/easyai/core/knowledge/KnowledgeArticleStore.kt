package com.easy.easyai.core.knowledge

import kotlinx.coroutines.flow.Flow

/**
 * Async knowledge article storage interface using Exposed R2DBC.
 * All operations are non-blocking and return suspend functions or Flow.
 */
interface KnowledgeArticleStore {
    /**
     * Save a knowledge article. Inserts if the ID doesn't exist, updates otherwise.
     */
    suspend fun save(article: KnowledgeArticle)

    /**
     * Find an article by its knowledge key for a specific user.
     */
    suspend fun findByKey(userId: String, knowledgeKey: String): KnowledgeArticle?

    /**
     * Find an article by ID.
     */
    suspend fun findById(id: String): KnowledgeArticle?

    /**
     * Find an article explicitly granted to [granteeId] by [knowledgeKey].
     * Keys are only unique per owner, so when several granted rows share the key the newest wins.
     */
    suspend fun findGrantedByKey(granteeId: String, knowledgeKey: String): KnowledgeArticle?

    /**
     * List all articles visible to a user, including:
     * - Articles owned by the user
     * - Articles with visibility='group' owned by users in the same group
     * - Every article in a shared owner bucket (group-shared knowledge), regardless of visibility
     * - Articles explicitly granted via knowledge_article_access
     *
     * @param userId the user requesting the list
     * @param groupUserIds optional list of group member IDs for group visibility
     * @param sharedOwnerIds owner buckets whose whole article set is shared with the caller
     * @param source optional filter by source
     * @param category optional filter by category
     */
    fun findAllVisible(
        userId: String,
        groupUserIds: List<String> = emptyList(),
        sharedOwnerIds: List<String> = emptyList(),
        source: String? = null,
        category: String? = null
    ): Flow<KnowledgeArticle>

    /**
     * Delete an article by ID.
     */
    suspend fun delete(id: String)

    /**
     * Delete all articles for a user.
     */
    suspend fun deleteByUser(userId: String)
}
