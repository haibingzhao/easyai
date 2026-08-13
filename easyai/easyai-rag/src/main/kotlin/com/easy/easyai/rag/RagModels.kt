package com.easy.easyai.rag

/**
 * Content category stored in EasyRAG. Keeps the abstraction content-type agnostic:
 * phase 1 uses [MEMORY]; phase 2 adds wiki pages via [WIKI].
 */
enum class RagCategory(val code: String) {
    /** Agent memories (user / feedback / project / reference). */
    MEMORY("memory"),

    /** Project wiki pages (phase 2). */
    WIKI("wiki")
}

/**
 * Per-document processing options passed to EasyRAG ingestion.
 *
 * @param skipKg skip knowledge-graph extraction (memories use `true`)
 * @param buildStructure build hierarchical structure for agentic retrieval (wiki, phase 2)
 * @param chunkMethod optional chunk method override
 * @param chunkTokenSize optional chunk token size override
 * @param chunkOverlap optional chunk overlap override
 */
data class RagProcessingOptions(
    val skipKg: Boolean = true,
    val buildStructure: Boolean = false,
    val chunkMethod: String? = null,
    val chunkTokenSize: Int? = null,
    val chunkOverlap: Int? = null
)

/**
 * A content unit to be stored in EasyRAG. Content-type agnostic: the [key] layout
 * and [metadata] are decided by the caller (memory store, wiki store, ...).
 *
 * @param category content category, used for externalId / filePath / metadata
 * @param key logical key within the category, e.g. `global/feedback/no-println.md` for memory
 * @param content full Markdown content
 * @param metadata string metadata passed through to chunks, filterable at query time
 * @param createTime business time in epoch seconds (typically the "updated" moment)
 * @param options document processing options
 */
data class RagDocument(
    val category: RagCategory,
    val key: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val createTime: Long,
    val options: RagProcessingOptions = RagProcessingOptions()
) {
    /** Deterministic external id: `easyai:{category}:{key}`. */
    val externalId: String get() = RagConstants.externalIdOf(category, key)

    /** Logical file path in EasyRAG: `easyai/{category}/{key}`. */
    val filePath: String get() = RagConstants.filePathOf(category, key)
}

/**
 * Result of an upsert operation.
 *
 * @param docId EasyRAG document id (deterministically derived from externalId)
 * @param indexed whether the synchronous indexing step completed (or was already done)
 * @param chunksCount chunk count reported by indexing, if available
 * @param unchanged true when the server skipped the write because content was identical
 */
data class RagUpsertResult(
    val docId: String,
    val indexed: Boolean,
    val chunksCount: Int? = null,
    val unchanged: Boolean = false
)

/**
 * Summary of a document returned by the list endpoint.
 */
data class RagDocInfo(
    val docId: String,
    val filePath: String,
    val status: String?,
    val externalId: String?,
    val contentSummary: String?,
    val contentLength: Int?,
    val chunksCount: Int?,
    val createdAt: Long?,
    val updatedAt: Long?
)

/**
 * Full document detail returned by read-by-external-id.
 */
data class RagDocumentDetail(
    val docId: String,
    val externalId: String?,
    val filePath: String?,
    val content: String?,
    val status: String?,
    val createTime: Long?,
    val chunksCount: Int?
)

/**
 * A retrieval chunk returned by `/api/query/data`.
 *
 * @param content chunk text
 * @param filePath logical file path of the source document
 * @param score relevance score
 * @param createTime business time of the source document in epoch seconds
 * @param metadata metadata passed through from the source document
 */
data class RagChunk(
    val content: String,
    val filePath: String?,
    val score: Double?,
    val createTime: Long?,
    val metadata: Map<String, Any?>
)

/** Shared naming conventions for EasyRAG integration. */
object RagConstants {
    /** Prefix of deterministic external ids. */
    const val EXTERNAL_ID_PREFIX = "easyai"

    /** Root segment of logical file paths. */
    const val FILE_PATH_ROOT = "easyai"

    @JvmStatic
    fun externalIdOf(category: RagCategory, key: String): String = "$EXTERNAL_ID_PREFIX:${category.code}:$key"

    @JvmStatic
    fun filePathOf(category: RagCategory, key: String): String = "$FILE_PATH_ROOT/${category.code}/$key"
}
