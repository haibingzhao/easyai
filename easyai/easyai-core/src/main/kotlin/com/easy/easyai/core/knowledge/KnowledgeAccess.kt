package com.easy.easyai.core.knowledge

/**
 * Per-document access control grant. Each row grants a specific permission ('read' or 'write')
 * to a specific user on a specific article.
 */
data class KnowledgeAccess(
    val id: String,
    val articleId: String,
    val granteeId: String,
    val permission: String,
    val grantedBy: String,
    val grantedAt: Long
)
