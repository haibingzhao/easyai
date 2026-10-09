package com.easy.easyai.autoconfigure.rag

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the EasyRAG integration.
 *
 * Prefix: easyai.rag
 *
 * [enabled] is the integration master switch. The remaining fields are the optional deployment-wide
 * STATIC layer: when [baseUrl] is set, the RAG connection is pinned from configuration for every user,
 * `~/.easyai/rag.json` is bypassed, and the Settings → RAG form becomes read-only. This is the B/S
 * multi-tenant mode — credentials then live in config, never in the file a member could edit. Leaving
 * [baseUrl] blank keeps the historical file-driven behavior (desktop / single-tenant).
 */
@ConfigurationProperties(prefix = "easyai.rag")
data class RagProperties(
    /** Whether the RAG integration is enabled. */
    var enabled: Boolean = true,

    /** EasyRAG server base URL; non-blank turns on the deployment-wide STATIC layer. */
    var baseUrl: String = "",

    /** Optional credential for JWT login. */
    var username: String = "",

    /** Optional credential for JWT login. Server-side only, never echoed to the console. */
    var password: String = "",

    /** Optional EasyRAG workspace (blank = server default). */
    var workspace: String = "",

    /** Default retrieval top-k. */
    var topK: Int = 5,

    /** Timeout for read operations (search / read / list). */
    var readTimeoutMs: Long = 5000,

    /** Timeout for document insert / delete operations. */
    var indexTimeoutMs: Long = 30000,

    /** Timeout for the index submission call (async mode). */
    var indexSubmitTimeoutMs: Long = 10_000,

    /** Initial interval between status polls during indexing. */
    var indexPollIntervalMs: Long = 2_000,

    /** Maximum total time to poll for indexing completion. */
    var indexPollMaxMs: Long = 300_000
) {
    /** True when a deployment-wide STATIC layer is configured (a base URL is pinned). */
    fun isStatic(): Boolean = baseUrl.isNotBlank()
}
