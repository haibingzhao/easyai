package com.easy.easyai.autoconfigure.rag

import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.rag.RagClient
import com.easy.easyai.rag.RagConfig
import com.easy.easyai.rag.RagKnowledgeStores
import com.easy.easyai.rag.RagMemoryStores
import com.easy.easyai.rag.RagSkillStores
import org.springframework.beans.factory.InitializingBean
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
 * - `skillStore`: RAG-backed skill retrieval index (when `easyai.skills.rag.enabled=true`).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RagClient::class)
@ConditionalOnProperty(prefix = "easyai.rag", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RagProperties::class)
class RagAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    fun ragClient(): RagClient = RagClient.create()

    /**
     * Installs the deployment-wide STATIC RAG config from `easyai.rag.*` before any request reads it.
     * A no-op when no base URL is pinned, which leaves `~/.easyai/rag.json` in charge (file mode).
     */
    @Bean
    fun ragStaticOverrideInitializer(properties: RagProperties): InitializingBean = InitializingBean {
        if (properties.isStatic()) {
            RagConfig.setStaticOverride(
                RagConfig(
                    enabled = properties.enabled,
                    baseUrl = properties.baseUrl,
                    username = properties.username.ifBlank { null },
                    password = properties.password.ifBlank { null },
                    workspace = properties.workspace.ifBlank { null },
                    topK = properties.topK,
                    readTimeoutMs = properties.readTimeoutMs,
                    indexTimeoutMs = properties.indexTimeoutMs,
                    indexSubmitTimeoutMs = properties.indexSubmitTimeoutMs,
                    indexPollIntervalMs = properties.indexPollIntervalMs,
                    indexPollMaxMs = properties.indexPollMaxMs
                )
            )
        }
    }

    @Bean
    @ConditionalOnProperty(prefix = "easyai.memory", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun memoryStore(ragClient: RagClient): MemoryStore = RagMemoryStores.create(ragClient)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.knowledge", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    fun knowledgeStore(ragClient: RagClient): KnowledgeStore = RagKnowledgeStores.create(ragClient)

    /**
     * Skill retrieval index. Unlike memory/knowledge this is **off by default**
     * (`matchIfMissing = false`): turning it on suppresses the eager skill listing in the
     * system prompt, so it must be an explicit operator decision.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.skills.rag", name = ["enabled"], havingValue = "true", matchIfMissing = false)
    fun skillStore(ragClient: RagClient): SkillStore = RagSkillStores.create(ragClient)
}
