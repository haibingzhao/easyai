package com.easy.easyai.rag

/**
 * Per-document processing options passed to EasyRAG ingestion.
 *
 * @param skipKg skip knowledge-graph extraction; defaults to `false` so the
 *   server builds the graph (passing `true` leaves the graph tables empty)
 * @param buildStructure build hierarchical structure for agentic retrieval (knowledge base, phase 2)
 * @param chunkMethod optional chunk method override
 * @param chunkTokenSize optional chunk token size override
 * @param chunkOverlap optional chunk overlap override
 */
data class RagProcessingOptions(
    val skipKg: Boolean = false,
    val buildStructure: Boolean = false,
    val chunkMethod: String? = null,
    val chunkTokenSize: Int? = null,
    val chunkOverlap: Int? = null
)

/**
 * A content unit to be stored in EasyRAG. Content-type isolation is achieved at
 * the storage level via `biz_id` (see [RagBizIdResolver]); no category field is
 * needed in the document itself.
 *
 * @param key logical key within the content type, e.g. `global/feedback/no-println.md` for memory
 * @param content full Markdown content
 * @param metadata string metadata passed through to chunks, filterable at query time
 * @param createTime business time in epoch seconds (typically the "updated" moment)
 * @param options document processing options
 */
data class RagDocument(
    val key: String,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val createTime: Long,
    val options: RagProcessingOptions = RagProcessingOptions()
) {
    /** Deterministic external id: `easyai:{key}`. */
    val externalId: String get() = RagConstants.externalIdOf(key)

    /** Logical file path in EasyRAG: `easyai/{key}`. */
    val filePath: String get() = RagConstants.filePathOf(key)
}

/**
 * Result of an upsert operation.
 *
 * @param docId EasyRAG document id (deterministically derived from externalId)
 * @param indexed whether indexing reached the terminal `processed` state (either
 *   synchronously from the index endpoint or via status polling); false when the poll times out
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

/**
 * Workspace-granular tenant configuration from EasyRAG (GET response).
 *
 * Mirrors the `tenant_config` table in EasyRAG. All fields except [workspace]
 * are nullable — `null` means "use global server default".
 * API keys are masked by the server (first 4 chars + `****`).
 */
data class RagWorkspaceConfig(
    val workspace: String,
    val llmModel: String? = null,
    val llmApiKey: String? = null,
    val llmBaseUrl: String? = null,
    val llmTemperature: Float? = null,
    val llmMaxTokens: Int? = null,
    val embeddingModel: String? = null,
    val embeddingApiKey: String? = null,
    val embeddingBaseUrl: String? = null,
    val embeddingDim: Int? = null,
    val chunkSize: Int? = null,
    val chunkOverlapSize: Int? = null,
    val language: String? = null,
    val defaultTopK: Int? = null,
    val rerankEnabled: Boolean? = null,
    val createdAt: String? = null,
    val updatedAt: String? = null
)

/**
 * Request body for upserting workspace tenant configuration.
 *
 * All fields except [workspace] are optional; `null` means "do not modify".
 * API keys sent as `"****"` or empty string preserve the existing value.
 */
data class RagWorkspaceConfigUpdate(
    val workspace: String,
    val llmModel: String? = null,
    val llmApiKey: String? = null,
    val llmBaseUrl: String? = null,
    val llmTemperature: Float? = null,
    val llmMaxTokens: Int? = null,
    val embeddingModel: String? = null,
    val embeddingApiKey: String? = null,
    val embeddingBaseUrl: String? = null,
    val embeddingDim: Int? = null,
    val chunkSize: Int? = null,
    val chunkOverlapSize: Int? = null,
    val language: String? = null,
    val defaultTopK: Int? = null,
    val rerankEnabled: Boolean? = null
)

/** Shared naming conventions for EasyRAG integration. */
object RagConstants {
    /** Prefix of deterministic external ids. */
    const val EXTERNAL_ID_PREFIX = "easyai"

    /** Root segment of logical file paths. */
    const val FILE_PATH_ROOT = "easyai"

    @JvmStatic
    fun externalIdOf(key: String): String = "$EXTERNAL_ID_PREFIX:$key"

    @JvmStatic
    fun filePathOf(key: String): String = "$FILE_PATH_ROOT/$key"
}
