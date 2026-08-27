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
 * Client abstraction over the EasyRAG REST API, content-type agnostic.
 * Content-type isolation (memory vs knowledge) is achieved at the storage level
 * via `biz_id` — see [RagBizIdResolver].
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
     * then submit indexing.
     *
     * @param bizId optional EasyRAG business-line slice; null = server default.
     * @param awaitIndexing when true (default), poll until indexing reaches a terminal
     *   state (`processed` / `failed`) or the poll timeout expires; when false, return
     *   right after the indexing submission is accepted (fire-and-forget), unless the
     *   server already reports a terminal status synchronously.
     */
    suspend fun upsert(doc: RagDocument, bizId: String? = null, awaitIndexing: Boolean = true): RagUpsertResult

    /** Delete the document bound to [externalId]; missing documents are ignored. */
    suspend fun delete(externalId: String, bizId: String? = null)

    /** Batch delete documents by their EasyRAG doc ids; individual failures are collected, not thrown. */
    suspend fun batchDelete(docIds: List<String>, bizId: String? = null): Int

    /** Read full document by [externalId]; returns null when not found. */
    suspend fun readByExternalId(externalId: String, bizId: String? = null): RagDocumentDetail?

    /**
     * List documents whose logical file path starts with [pathPrefix].
     * Fetches all pages transparently.
     */
    suspend fun list(pathPrefix: String, bizId: String? = null): List<RagDocInfo>

    /**
     * Semantic retrieval via `POST /api/query/data` (naive mode, no LLM).
     * Content-type isolation is handled by [bizId] at the storage layer.
     *
     * @param query natural language query
     * @param filters additional exact metadata filters
     * @param topK max chunks to return
     * @param timeRangeStart inclusive business-time lower bound, epoch seconds
     * @param timeRangeEnd inclusive business-time upper bound, epoch seconds
     * @param bizId optional EasyRAG business-line slice; null = server default
     */
    suspend fun search(
        query: String,
        filters: Map<String, String> = emptyMap(),
        topK: Int = 5,
        timeRangeStart: Long? = null,
        timeRangeEnd: Long? = null,
        bizId: String? = null
    ): List<RagChunk>

    /**
     * Get the workspace-granular tenant configuration from EasyRAG.
     * Returns `null` when no workspace-specific config exists (global defaults apply).
     */
    suspend fun getWorkspaceConfig(workspace: String): RagWorkspaceConfig?

    /**
     * Create or update the tenant configuration for a workspace.
     * Uses merge semantics: `null` fields in [config] do not modify existing values.
     * API keys sent as `"****"` or empty string also preserve the existing value.
     */
    suspend fun upsertWorkspaceConfig(config: RagWorkspaceConfigUpdate): RagWorkspaceConfig

    /**
     * Delete the tenant configuration for a workspace, reverting to global server defaults.
     */
    suspend fun deleteWorkspaceConfig(workspace: String)

    companion object {
        /** Creates the default HTTP-based client reading [RagConfig] from [configPath] per request. */
        @JvmStatic
        fun create(configPath: Path = RagConfig.defaultConfigPath()): RagClient = EasyRagClient(configPath)
    }
}
