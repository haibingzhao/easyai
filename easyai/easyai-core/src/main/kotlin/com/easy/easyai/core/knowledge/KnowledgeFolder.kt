package com.easy.easyai.core.knowledge

/**
 * A node of the knowledge folder tree.
 *
 * Folders are the only persisted hierarchy: the display path is derived from [parentId] ancestors
 * and the article title, never stored. A null [parentId] marks a tree root (a knowledge base).
 */
data class KnowledgeFolder(
    val id: String,
    val userId: String,
    val parentId: String?,
    val name: String,
    /** The per-bucket "Uncategorized" folder; can be renamed but not deleted. */
    val isDefault: Boolean = false,
    val createdBy: String? = null,
    val createdAt: Long,
    val updatedAt: Long
)
