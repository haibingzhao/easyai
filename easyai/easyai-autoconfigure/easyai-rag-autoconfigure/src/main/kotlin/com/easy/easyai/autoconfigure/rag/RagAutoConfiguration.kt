package com.easy.easyai.autoconfigure.rag

import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationRunLifecycle
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSessionTitlePort
import com.easy.easyai.core.memory.consolidation.MemoryConsolidationSettingsStore
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.MemoryConsolidationTaskHandler
import com.easy.easyai.core.task.TaskHandler
import com.easy.easyai.rag.RagClient
import com.easy.easyai.rag.RagConfig
import com.easy.easyai.rag.RagKnowledgeStores
import com.easy.easyai.rag.RagMemoryStores
import com.easy.easyai.rag.RagSkillStores
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
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
 * - `memoryConsolidationAgent`: multi-level memory consolidation through a hidden session
 *   (when `easyai.memory.consolidation.available=true`, the default).
 *
 * The consolidation agent needs a [SessionManager] and an [AgentService] to run a real Agent Loop,
 * but those beans live in the repository/web autoconfigurations this module does not depend on. They
 * are injected through [ObjectProvider] and validated at run time, so this context stays
 * constructible on its own.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(RagClient::class)
@ConditionalOnProperty(prefix = "easyai.rag", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(RagProperties::class, MemoryConsolidationProperties::class)
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
    fun memoryStore(ragClient: RagClient, domainCatalog: DomainCatalog): MemoryStore =
        RagMemoryStores.create(ragClient, domainCatalog.memoryCodes())

    /**
     * Deployment-level limits consumed by the agent and the scheduler; always present so callers
     * can read `available` even when the agent bean itself is not created.
     */
    @Bean
    @ConditionalOnMissingBean
    fun memoryConsolidationSettings(properties: MemoryConsolidationProperties): MemoryConsolidationSettings =
        MemoryConsolidationSettings(
            available = properties.available,
            maxGroupingPromptChars = properties.maxGroupingPromptChars,
            maxSourcesPerGroup = properties.maxSourcesPerGroup,
            maxGroupSourceChars = properties.maxGroupSourceChars,
            clustersPerMergeCall = properties.clustersPerMergeCall,
            maxMergePromptCharsPerCall = properties.maxMergePromptCharsPerCall,
            maxArchivesPerRun = properties.maxArchivesPerRun,
            maxIterations = properties.maxIterations,
            runTimeoutMs = properties.runTimeoutMs,
            dryRunTimeoutMs = properties.dryRunTimeoutMs,
            defaultThreshold = properties.defaultThreshold,
            dailyCron = properties.dailyCron,
            maxTasksPerTickPerUser = properties.maxTasksPerTickPerUser,
            maxLevel = properties.maxLevel,
            zeroGainBackoffBaseDays = properties.zeroGainBackoffBaseDays,
            zeroGainBackoffMaxDays = properties.zeroGainBackoffMaxDays,
            failedRunBackoffBaseDays = properties.failedRunBackoffBaseDays
        )

    /**
     * Multi-level memory consolidation. Enabled unless the deployment sets
     * `easyai.memory.consolidation.available=false`; per-user opt-in is a runtime setting checked
     * by the web layer and the scheduler, not a bean condition.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(
        prefix = "easyai.memory",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    @ConditionalOnProperty(
        prefix = "easyai.memory.consolidation",
        name = ["available"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun memoryConsolidationAgent(
        memoryStore: MemoryStore,
        settings: MemoryConsolidationSettings,
        sessionManager: ObjectProvider<SessionManager>,
        agentService: ObjectProvider<AgentService>,
        auxModelResolver: ObjectProvider<AuxModelResolver>,
        lifecycle: ObjectProvider<MemoryConsolidationRunLifecycle>
    ): MemoryConsolidationAgent = MemoryConsolidationAgent(
        store = memoryStore,
        sessionManager = sessionManager.ifAvailable,
        agentService = agentService.ifAvailable,
        settings = settings,
        auxResolver = auxModelResolver.ifAvailable,
        lifecycle = lifecycle.ifAvailable
    )

    /**
     * Bridges the async-task subsystem to [memoryConsolidationAgent]: registered as a [TaskHandler]
     * so the app-level runner can execute consolidation tasks that outlive their session.
     *
     * Guarded on the concrete type, exactly like the planner bean: a bare `@ConditionalOnMissingBean`
     * reads this method's return type, so with [TaskHandler] here any handler registered earlier (and
     * "add a type, add a handler bean" is the whole design) would silently drop consolidation from
     * the runner's dispatch table.
     */
    @Bean
    @ConditionalOnMissingBean(MemoryConsolidationTaskHandler::class)
    @ConditionalOnProperty(
        prefix = "easyai.memory",
        name = ["enabled"],
        havingValue = "true",
        matchIfMissing = true
    )
    @ConditionalOnProperty(
        prefix = "easyai.memory.consolidation",
        name = ["available"],
        havingValue = "true",
        matchIfMissing = true
    )
    fun memoryConsolidationTaskHandler(
        agent: MemoryConsolidationAgent,
        settings: MemoryConsolidationSettings,
        domainCatalog: DomainCatalog,
        taskStore: ObjectProvider<AsyncTaskStore>,
        titlePort: ObjectProvider<MemoryConsolidationSessionTitlePort>,
        memoryStore: ObjectProvider<MemoryStore>,
        settingsStore: ObjectProvider<MemoryConsolidationSettingsStore>
    ): MemoryConsolidationTaskHandler = MemoryConsolidationTaskHandler(
        agent = agent,
        taskStore = taskStore.ifAvailable,
        titlePort = titlePort.ifAvailable,
        memoryStore = memoryStore.ifAvailable,
        settingsStore = settingsStore.ifAvailable,
        backoffBaseDays = settings.zeroGainBackoffBaseDays,
        backoffMaxDays = settings.zeroGainBackoffMaxDays,
        failedRunBackoffBaseDays = settings.failedRunBackoffBaseDays,
        memoryCodes = domainCatalog.memoryCodes().toSet()
    )

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
