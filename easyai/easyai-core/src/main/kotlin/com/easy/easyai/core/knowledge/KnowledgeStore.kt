package com.easy.easyai.core.knowledge

/**
 * A retrieval hit from the knowledge index.
 *
 * @param id stable article id; use it for `knowledge_read`
 * @param kcategory knowledge category code (e.g. "architecture")
 * @param ext file extension without dot (e.g. "md", "kt")
 * @param content preview of the indexed body
 */
data class KnowledgeEntry(
    val id: String,
    val title: String,
    val description: String,
    val kcategory: String,
    val ext: String,
    val content: String,
    val updatedAt: Long?,
    val chunksCount: Int?
)

/**
 * The knowledge retrieval index.
 *
 * Only published articles with `ragEnabled` are indexed. Directory layout lives in the database,
 * not in this store: an entry is addressed by the article id alone.
 */
interface KnowledgeStore {

    /** Semantic retrieval over the index. */
    suspend fun search(userId: String, query: String, topK: Int = DEFAULT_TOP_K): List<KnowledgeEntry>

    /** Full indexed body; null when the entry is absent. */
    suspend fun read(userId: String, id: String): String?

    /** Whether the index accepts this file extension (upload validation). */
    fun supports(ext: String): Boolean = true

    /** Insert or re-index one article. Idempotent. */
    suspend fun index(
        userId: String,
        id: String,
        content: String,
        title: String? = null,
        kcategory: String? = null,
        ext: String = "md"
    ): Boolean

    /** Remove one article from the index. */
    suspend fun delete(userId: String, id: String): Boolean

    /** Chunk counts for the ids that finished indexing; missing ids are still being processed. */
    suspend fun chunkCounts(userId: String, ids: Collection<String>): Map<String, Int>

    companion object {
        const val DEFAULT_TOP_K = 8
    }
}
