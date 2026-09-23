package com.easy.easyai.autoconfigure.core

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.common.textio.template.JinjavaTemplateRenderer
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.prompt.DefaultProviderPromptLoader
import com.easy.easyai.core.prompt.PromptTemplateService
import com.easy.easyai.core.prompt.ProviderPromptLoader
import com.easy.easyai.core.prompt.SystemPromptBuilder
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.tool.DefaultToolExecutionEngine
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolExecutionEngine
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.core.validation.InputSchemaValidator
import com.easy.easyai.core.validation.OutputSchemaCompletionCheck
import com.easy.easyai.core.validation.OutputSchemaValidator
import com.easy.easyai.skills.*
import com.easy.easyai.skills.a2a.AgentSkillFactory
import com.easy.easyai.skills.a2a.DefaultAgentSkillFactory
import com.easy.easyai.skills.command.*
import com.easy.easyai.tools.SpringToolFactory
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Lazy
import org.slf4j.LoggerFactory

@AutoConfiguration
@ComponentScan(basePackages = ["com.easy.easyai.core", "com.easy.easyai.agent", "com.easy.easyai.tools", "com.easy.easyai.skills", "com.easy.easyai.repository"])
@EnableConfigurationProperties(EasyAiProperties::class)
open class EasyAiCoreAutoConfiguration(
    private val properties: EasyAiProperties
) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /**
     * Propagate the configured domain to [DomainCatalog] so all components
     * (memory, knowledge, controllers) see a consistent category set.
     * Uses @PostConstruct for reliable initialization order in Spring Boot 4.x.
     */
    @PostConstruct
    open fun configureDomain() {
        DomainCatalog.activeDomain = properties.domain
    }

    @Bean
    @ConditionalOnMissingBean
    open fun messageConverter(objectStorageResolver: ObjectProvider<ObjectStorageResolver>): MessageConverter =
        DefaultMessageConverter(objectStorageResolver = objectStorageResolver.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    open fun toolExecutionEngine(properties: EasyAiProperties): ToolExecutionEngine =
        DefaultToolExecutionEngine()

    @Bean
    @ConditionalOnMissingBean
    open fun toolFactory(builders: List<ToolBuilder>): ToolFactory = SpringToolFactory(builders)

    // ========== Agent System Prompt Beans ==========

    @Bean
    @ConditionalOnMissingBean
    open fun providerPromptLoader(): ProviderPromptLoader = DefaultProviderPromptLoader()

    @Bean
    @ConditionalOnMissingBean
    open fun systemPromptBuilder(loader: ProviderPromptLoader): SystemPromptBuilder =
        SystemPromptBuilder(loader)

    @Bean
    @ConditionalOnMissingBean
    open fun templateRenderer(): TemplateRenderer = JinjavaTemplateRenderer()

    @Bean
    @ConditionalOnMissingBean
    open fun promptTemplateService(
        renderer: TemplateRenderer,
        systemPromptBuilder: SystemPromptBuilder
    ): PromptTemplateService = PromptTemplateService(renderer, systemPromptBuilder)


    // ========== Skill Beans ==========

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.skills", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillDiscovery(): SkillDiscovery = DefaultSkillDiscovery()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.skills", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillRegistry(discovery: SkillDiscovery, properties: EasyAiProperties): SkillRegistry {
        return DefaultSkillRegistry(discovery, skillConfigOf(properties))
    }

    /**
     * Registry-facing view of `easyai.skills.*` as a bean, so collaborators outside this class
     * (e.g. [com.easy.easyai.skills.SkillToolBuilder]) resolve granularity the same way the
     * registry does instead of re-deriving from properties.
     */
    @Bean
    @ConditionalOnMissingBean
    open fun skillConfig(properties: EasyAiProperties): SkillConfig = skillConfigOf(properties)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.skills", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillAccessResolver(
        skillRegistry: SkillRegistry,
        catalog: ObjectProvider<AsyncSkillCatalogStore>,
        skillConfig: SkillConfig
    ): SkillAccessResolver = SkillAccessResolver(skillRegistry, catalog.getIfAvailable(), skillConfig)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.skills", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun agentSkillFactory(): AgentSkillFactory = DefaultAgentSkillFactory()

    // ========== Skill Prompt / RAG Beans ==========

    /**
     * Not RAG-gated: it also carries the `enabled` filtering that must apply while the full list is
     * still injected, so both prompt paths (default agent and DB sessions) share one decision.
     */
    @Bean
    @ConditionalOnMissingBean
    open fun skillPromptSource(
        @Autowired(required = false) skillRegistry: SkillRegistry? = null,
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
        @Autowired(required = false) skillStore: SkillStore? = null,
        properties: EasyAiProperties
    ): SkillPromptSource = SkillPromptSource(
        registry = skillRegistry,
        catalog = catalog,
        injectIntoSystemPrompt = properties.skills.injectIntoSystemPrompt,
        ragEnabled = properties.skills.rag.enabled,
        // Discovery is only "ready" when the whole chain exists. RagAutoConfiguration hands out a
        // SkillStore whenever the flag is on, but the write side (SkillIndexStartupRunner → 
        // SkillCatalogSyncService.claimUnclaimed) needs the R2DBC catalog to have anything to index.
        // Without this conjunction, `rag on + r2dbc off` would suppress the prompt listing while 
        // skill_search returns empty forever — the worst of both worlds.
        ragDiscoveryReady = skillStore != null && catalog != null,
        config = skillConfigOf(properties)
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_RAG_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = false)
    open fun skillCatalogSyncService(
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
        skillConfig: SkillConfig
    ): SkillCatalogSyncService = SkillCatalogSyncService(catalog, skillConfig)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_RAG_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = false)
    open fun skillIndexer(
        skillCatalogSyncService: SkillCatalogSyncService,
        @Autowired(required = false) skillStore: SkillStore? = null,
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
        properties: EasyAiProperties
    ): SkillIndexer = SkillIndexer(
        skillStore = skillStore,
        catalog = catalog,
        syncService = skillCatalogSyncService,
        config = skillConfigOf(properties),
        indexConcurrency = properties.skills.rag.indexConcurrency
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_RAG_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = false)
    open fun skillCatalogService(
        skillIndexer: SkillIndexer,
        @Autowired(required = false) skillStore: SkillStore? = null,
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
        properties: EasyAiProperties
    ): SkillCatalogService = SkillCatalogService(
        catalog = catalog,
        indexer = skillIndexer,
        skillStore = skillStore,
        config = skillConfigOf(properties)
    )

    /**
     * The one refresh chain — re-read the disk, claim catalog rows and reconcile the index — shared
     * by the startup pass below and by the `refresh_skills` tool.
     *
     * Created only when the whole chain is present: a registry with nothing to index into or no table
     * to read owners from would make the pass a no-op that still costs a listener.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_RAG_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = false)
    open fun skillRefreshService(
        skillIndexer: SkillIndexer,
        skillCatalogSyncService: SkillCatalogSyncService,
        skillConfig: SkillConfig,
        @Autowired(required = false) skillRegistry: SkillRegistry? = null,
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
        @Autowired(required = false) skillStore: SkillStore? = null
    ): SkillRefreshService? {
        if (skillRegistry == null || catalog == null || skillStore == null) {
            logger.warn(
                "Skill RAG is enabled but registry={}, catalog={}, skillStore={}: skill refresh is off",
                skillRegistry != null, catalog != null, skillStore != null
            )
            return null
        }
        return SkillRefreshService(
            registry = skillRegistry,
            catalog = catalog,
            syncService = skillCatalogSyncService,
            indexer = skillIndexer,
            config = skillConfig
        )
    }

    /**
     * Thin event shell over [SkillRefreshService]: without that service there is nothing to run, so the
     * runner is absent as well and only the application-event wiring is missing.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_RAG_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = false)
    open fun skillIndexStartupRunner(
        @Autowired(required = false) skillRefreshService: SkillRefreshService? = null
    ): SkillIndexStartupRunner? = skillRefreshService?.let { SkillIndexStartupRunner(it) }

    // ========== Validation Beans ==========

    @Bean
    @ConditionalOnMissingBean
    open fun outputSchemaValidator(): OutputSchemaValidator = OutputSchemaValidator()

    @Bean
    @ConditionalOnMissingBean
    open fun inputSchemaValidator(): InputSchemaValidator = InputSchemaValidator()

    @Bean
    @ConditionalOnMissingBean
    open fun outputSchemaCompletionCheck(validator: OutputSchemaValidator): OutputSchemaCompletionCheck =
        OutputSchemaCompletionCheck(validator)

    // ========== Command Beans ==========

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.commands", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun commandRegistry(
        @Autowired(required = false) promptProvider: McpPromptProvider? = null,
        @Autowired(required = false) builtinHandlers: List<BuiltinCommandHandler>? = null,
    ): CommandRegistry {
        return DefaultCommandRegistry(promptProvider, builtinHandlers ?: emptyList())
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.commands", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun commandService(
        commandRegistry: CommandRegistry,
        skillAccessResolver: ObjectProvider<SkillAccessResolver>,
        skillConfig: SkillConfig,
        @Autowired(required = false) promptProvider: McpPromptProvider? = null,
        @Autowired(required = false) userCommandStore: AsyncUserCommandStore? = null,
        @Autowired(required = false) builtinHandlers: List<BuiltinCommandHandler>? = null,
    ): CommandService = CommandService(
        commandRegistry, promptProvider, userCommandStore, builtinHandlers ?: emptyList(),
        skillAccessResolver.getIfAvailable(), skillConfig
    )

    @Bean
    @ConditionalOnMissingBean(AgentService::class)
    open fun agentService(
        chatModelFactories: List<ChatModelFactory>,
        chatModel: ChatModel,
        messageConverter: MessageConverter,
        toolExecutionEngine: ToolExecutionEngine,
        promptTemplateService: PromptTemplateService,
        transformContextService: TransformContextService?,
        @Lazy toolFactory: ToolFactory,
        properties: EasyAiProperties,
        @Autowired(required = false)
        memoryStore: MemoryStore? = null,
        @Autowired(required = false)
        knowledgeStore: KnowledgeStore? = null,
        @Autowired(required = false)
        mediaProviderResolver: com.easy.easyai.core.media.MediaProviderResolver? = null,
        @Autowired(required = false)
        objectStorageResolver: com.easy.easyai.core.storage.ObjectStorageResolver? = null,
        @Autowired(required = false)
        permissionService: PermissionService? = null,
        @Autowired(required = false)
        eventListeners: List<AgentEventListener>? = emptyList(),
        @Autowired(required = false)
        completionChecks: List<AgentCompletionCheck>? = emptyList(),
        @Autowired(required = false)
        observationRegistry: ObservationRegistry? = null,
        @Autowired(required = false)
        outputSchemaValidator: OutputSchemaValidator? = null,
        @Autowired(required = false)
        waitForUserListener: WaitForUserListener? = null,
        @Value("\${easyai.observability.enabled:true}")
        observabilityEnabled: Boolean = true
    ): AgentService {
        val registry = if (observabilityEnabled) (observationRegistry ?: ObservationRegistry.NOOP) else ObservationRegistry.NOOP
        return DefaultAgentService(
            chatModelFactories = chatModelFactories,
            messageConverter = messageConverter,
            toolExecutor = toolExecutionEngine,
            promptTemplateService = promptTemplateService,
            defaultChatModel = chatModel,
            transformContextService = transformContextService ?: DefaultTransformContextService(),
            permissionService = permissionService,
            toolFactory = toolFactory,
            eventListeners = eventListeners ?: emptyList(),
            completionChecks = completionChecks ?: emptyList(),
            observationRegistry = registry,
            memoryStore = memoryStore,
            knowledgeStore = knowledgeStore,
            mediaProviderResolver = mediaProviderResolver,
            objectStorageResolver = objectStorageResolver,
            waitForUserListener = waitForUserListener,
            outputSchemaValidator = outputSchemaValidator
        )
    }

    /** Registry-facing view of `easyai.skills.*`; shared by the registry and every RAG service. */
    private fun skillConfigOf(properties: EasyAiProperties): SkillConfig = SkillConfig(
        enabled = properties.skills.enabled,
        paths = properties.skills.paths,
        homeSkillDirs = properties.skills.homeSkillDirs,
        injectIntoSystemPrompt = properties.skills.injectIntoSystemPrompt,
        workDir = properties.workDir,
    )

    companion object {
        /** Master switch of the whole skill retrieval chain. */
        private const val SKILL_RAG_PREFIX = "easyai.skills.rag"
    }
}
