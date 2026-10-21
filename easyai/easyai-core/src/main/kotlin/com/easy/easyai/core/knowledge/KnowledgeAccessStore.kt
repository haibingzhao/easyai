package com.easy.easyai.core.knowledge

/**
 * Async knowledge article access control storage interface using Exposed R2DBC.
 * Manages per-document grants for read/write permissions.
 */
interface KnowledgeAccessStore {
    /**
     * Grant a permission to a user on an article.
     * If a grant already exists for (articleId, granteeId), it is replaced.
     */
    suspend fun grant(access: KnowledgeAccess)

    /**
     * Revoke all permissions for a user on an article.
     */
    suspend fun revoke(articleId: String, granteeId: String)

    /**
     * List all grants for a specific article.
     */
    suspend fun listByArticle(articleId: String): List<KnowledgeAccess>

    /**
     * List all grants for a specific user (grantee).
     */
    suspend fun listByGrantee(granteeId: String): List<KnowledgeAccess>

    /**
     * Check if a user has a specific permission on an article.
     */
    suspend fun hasPermission(articleId: String, granteeId: String, permission: String): Boolean

    /**
     * Delete all grants for an article (called when the article is deleted).
     */
    suspend fun deleteByArticle(articleId: String)
}
