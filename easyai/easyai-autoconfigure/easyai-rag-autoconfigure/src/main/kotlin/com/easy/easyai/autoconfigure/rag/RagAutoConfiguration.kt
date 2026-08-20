package com.easy.easyai.autoconfigure.rag

import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.rag.RagClient
import com.easy.easyai.rag.RagKnowledgeStores
import com.easy.easyai.rag.RagMemoryStores
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for the EasyRAG integration.
 *
 * Enabled by default (`easyai.rag.enabled=true`). Registers:
 * - `ragClient`: HTTP client for the EasyRAG REST API (config from `~/.easyai/rag.json`).
 * - `memoryStore`: RAG-backed memory store (when `easyai.memory.enabled=true`).
 * - `knowledgeStore`: RAG-backed knowledge base store (when `easyai.knowledge.enabled=true`).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RagClient::class)
@ConditionalOnProperty(prefix = "easyai.rag", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RagProperties::class)
class RagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun ragClient(): RagClient = RagClient.create()

    @Bean
    @ConditionalOnProperty(prefix = "easyai.memory", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun memoryStore(ragClient: RagClient): MemoryStore = RagMemoryStores.create(ragClient)

    @Bean
    @ConditionalOnProperty(prefix = "easyai.knowledge", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun knowledgeStore(ragClient: RagClient): KnowledgeStore = RagKnowledgeStores.create(ragClient)
}
