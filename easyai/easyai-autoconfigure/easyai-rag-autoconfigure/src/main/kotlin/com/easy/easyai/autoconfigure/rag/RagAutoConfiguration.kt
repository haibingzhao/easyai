package com.easy.easyai.autoconfigure.rag

import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.rag.RagClient
import com.easy.easyai.rag.RagMemoryStores
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the EasyRAG integration.
 *
 * Enabled when `easyai.rag.enabled=true`. Registers:
 * - `ragClient`: HTTP client for the EasyRAG REST API (config from `~/.easyai/rag.json`).
 * - `memoryStore`: RAG-backed memory store, the only [MemoryStore] implementation
 *   (no file-based fallback). It exists only when both `easyai.rag.enabled=true`
 *   and `easyai.memory.enabled=true`; without RAG configured the memory feature
 *   stays off entirely.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RagClient::class)
@ConditionalOnProperty(prefix = "easyai.rag", name = ["enabled"], havingValue = "true", matchIfMissing = false)
class RagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun ragClient(): RagClient = RagClient.create()

    @Bean
    @ConditionalOnProperty(prefix = "easyai.memory", name = ["enabled"], havingValue = "true", matchIfMissing = false)
    fun memoryStore(ragClient: RagClient): MemoryStore = RagMemoryStores.create(ragClient)
}
