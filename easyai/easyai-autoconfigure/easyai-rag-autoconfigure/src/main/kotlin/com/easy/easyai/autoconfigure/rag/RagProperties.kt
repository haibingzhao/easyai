package com.easy.easyai.autoconfigure.rag

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for the EasyRAG integration.
 *
 * Prefix: easyai.rag
 */
@ConfigurationProperties(prefix = "easyai.rag")
data class RagProperties(
    /** Whether the RAG integration is enabled. */
    var enabled: Boolean = true
)
