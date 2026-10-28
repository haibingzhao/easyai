package com.easy.easyai.core.knowledge

/**
 * Async knowledge folder storage. Folders are bucket-scoped storage metadata; access control is
 * decided by callers through [KnowledgeAccessPolicy].
 */
interface KnowledgeFolderStore {

    /** Every folder in the given owner buckets. */
    suspend fun listByBuckets(userIds: Collection<String>): List<KnowledgeFolder>

    suspend fun findById(id: String): KnowledgeFolder?

    /** The bucket's default ("Uncategorized") folder, if it exists. */
    suspend fun findDefault(userId: String): KnowledgeFolder?

    suspend fun save(folder: KnowledgeFolder)

    suspend fun delete(id: String)

    /**
     * Delete [id] only when nothing references it any more: no direct child folder and no article in
     * [subtreeIds]. Check and delete run in one transaction, so a concurrent upload cannot slip an
     * article into the folder between the emptiness check and the removal. Returns false when the
     * folder was not empty (or no longer exists).
     */
    suspend fun deleteIfEmpty(id: String, subtreeIds: Collection<String>): Boolean
}
