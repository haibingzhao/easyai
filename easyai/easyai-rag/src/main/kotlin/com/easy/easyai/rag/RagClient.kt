package com.easy.easyai.rag

import java.nio.file.Path

/**
 * Raised when an EasyRAG call fails (network, HTTP error, timeout).
 * Callers decide the degradation strategy (memory backend error, skip, ...).
 */
class RagException(
    message: String,
    val statusCode: Int? = null,
    cause: Throwable? = null
) : RuntimeException(message, cause)

/**
 * Client abstraction over the EasyRAG REST API, content-type agnostic
 * (memory in phase 1, wiki in phase 2 — see [RagCategory]).
 *
 * All methods are suspending and never block. Implementations reload [RagConfig]
 * per request so runtime configuration changes apply without restart.
 */
interface RagClient {

    /** Whether the RAG integration is enabled in config. */
    suspend fun isEnabled(): Boolean

    /** Connectivity check against `/api/health`. Returns false instead of throwing. */
    suspend fun healthCheck(): Boolean

    /**
     * Idempotent upsert: `POST /api/documents/text` with the deterministic externalId,
     * then synchronous indexing (`POST /api/documents/{docId}/index`) so the document
     * is searchable immediately after the call returns.
     *
     * @param bizId optional EasyRAG business-line slice; null = server default.
     */
    suspend fun upsert(doc: RagDocument, bizId: String? = null): RagUpsertResult

    /** Delete the document bound to [externalId]; missing documents are ignored. */
    suspend fun delete(externalId: String, bizId: String? = null)

    /** Batch delete documents by their EasyRAG doc ids; individual failures are collected, not thrown. */
    suspend fun batchDelete(docIds: List<String>, bizId: String? = null): Int

    /** Read full document by [externalId]; returns null when not found. */
    suspend fun readByExternalId(externalId: String, bizId: String? = null): RagDocumentDetail?

    /**
     * List documents under [category] whose logical file path starts with [pathPrefix].
     * Fetches all pages transparently.
     */
    suspend fun list(category: RagCategory, pathPrefix: String, bizId: String? = null): List<RagDocInfo>

    /**
     * Semantic retrieval via `POST /api/query/data` (naive mode, no LLM).
     *
     * @param query natural language query
     * @param category content category filter (metadata `category`)
     * @param filters additional exact metadata filters
     * @param topK max chunks to return
     * @param timeRangeStart inclusive business-time lower bound, epoch seconds
     * @param timeRangeEnd inclusive business-time upper bound, epoch seconds
     * @param bizId optional EasyRAG business-line slice; null = server default
     */
    suspend fun search(
        query: String,
        category: RagCategory,
        filters: Map<String, String> = emptyMap(),
        topK: Int = 5,
        timeRangeStart: Long? = null,
        timeRangeEnd: Long? = null,
        bizId: String? = null
    ): List<RagChunk>

    companion object {
        /** Creates the default HTTP-based client reading [RagConfig] from [configPath] per request. */
        @JvmStatic
        fun create(configPath: Path = RagConfig.defaultConfigPath()): RagClient = EasyRagClient(configPath)
    }
}
