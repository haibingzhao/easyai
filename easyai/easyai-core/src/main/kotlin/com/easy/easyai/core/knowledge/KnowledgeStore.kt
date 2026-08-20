package com.easy.easyai.core.knowledge

/**
 * Summary of a knowledge base entry returned by list operations.
 *
 * @param key logical key `{source}/{relativePath}`
 * @param source upload source (folder name or user-provided label)
 * @param relativePath path of the file within the source
 * @param title extracted or frontmatter title
 * @param description extracted or frontmatter description
 * @param kcategory knowledge category code (e.g. "architecture")
 * @param ext file extension without dot (e.g. "md", "kt")
 * @param content first ~200 chars of content for preview
 * @param updatedAt epoch milliseconds of last modification
 * @param chunksCount number of chunks after indexing, if available
 */
data class KnowledgeEntry(
    val key: String,
    val source: String,
    val relativePath: String,
    val title: String,
    val description: String,
    val kcategory: String,
    val ext: String,
    val content: String,
    val updatedAt: Long?,
    val chunksCount: Int?
)

/**
 * Full detail of a knowledge entry including relationship information.
 *
 * @param toc table of contents derived from Markdown headings
 * @param parent key of the parent index document, if any
 * @param children keys of child documents when this entry is a directory index
 * @param related keys of semantically related knowledge entries
 */
data class KnowledgeDetail(
    val entry: KnowledgeEntry,
    val fullContent: String,
    val toc: List<String>,
    val parent: String?,
    val children: List<String>,
    val related: List<String>
)

/**
 * Result of a single file upload within a batch.
 */
data class UploadResult(
    val relativePath: String,
    val success: Boolean,
    val key: String? = null,
    val reason: String? = null
)

/**
 * Input item for a knowledge upload batch.
 *
 * @param relativePath relative path within the source (used as key segment)
 * @param content file content as text
 */
data class KnowledgeUploadItem(
    val relativePath: String,
    val content: String
)

/**
 * Abstraction for knowledge base storage operations.
 *
 * Implementations decide the backend (EasyRAG, local files, etc.).
 * All methods are suspending and never block.
 */
interface KnowledgeStore {

    /**
     * Upload a batch of files to the knowledge base.
     *
     * @param userId owner user id for biz_id isolation
     * @param source label identifying the upload source (folder name or user-provided)
     * @param items files to upload
     * @param kcategory optional knowledge category to assign
     * @return per-file results; partial failures do not abort the batch
     */
    suspend fun uploadBatch(
        userId: String,
        source: String,
        items: List<KnowledgeUploadItem>,
        kcategory: String? = null
    ): List<UploadResult>

    /**
     * List knowledge entries, optionally filtered.
     *
     * @param userId owner user id
     * @param source filter by source (null = all)
     * @param kcategory filter by category (null = all)
     * @param query optional semantic search query
     */
    suspend fun list(
        userId: String,
        source: String? = null,
        kcategory: String? = null,
        query: String? = null
    ): List<KnowledgeEntry>

    /**
     * Read full detail of a knowledge entry including relationships.
     *
     * @param userId owner user id
     * @param key logical key `{source}/{relativePath}`
     */
    suspend fun detail(userId: String, key: String): KnowledgeDetail?

    /**
     * Read full document content by key without deriving relationships.
     * Lightweight path for LLM tools; default implementation delegates to [detail].
     * Implementations backed by remote storage SHOULD override this to avoid the
     * relationship-derivation cost of [detail].
     *
     * @param userId owner user id
     * @param key logical key `{source}/{relativePath}`
     */
    suspend fun read(userId: String, key: String): String? = detail(userId, key)?.fullContent

    /**
     * Delete a single knowledge entry.
     *
     * @return true if the entry existed and was deleted
     */
    suspend fun delete(userId: String, key: String): Boolean

    /**
     * Delete all knowledge entries from a given source.
     *
     * @return number of entries deleted
     */
    suspend fun deleteSource(userId: String, source: String): Int

    /**
     * List all distinct sources for the user.
     */
    suspend fun sources(userId: String): List<String>
}
