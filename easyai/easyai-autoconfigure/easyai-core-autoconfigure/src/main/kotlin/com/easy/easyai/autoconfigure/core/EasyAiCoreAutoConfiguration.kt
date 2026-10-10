package com.easy.easyai.autoconfigure.core

import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.common.textio.template.JinjavaTemplateRenderer
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelSettingsStore
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
import com.easy.easyai.core.tool.ToolContextProjector
import com.easy.easyai.core.tool.ToolExecutionEngine
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.core.validation.InputSchemaValidator
import com.easy.easyai.core.validation.OutputSchemaCompletionCheck
import com.easy.easyai.core.validation.OutputSchemaValidator
import com.easy.easyai.skills.*
import com.easy.easyai.skills.a2a.AgentSkillFactory
import com.easy.easyai.skills.a2a.DefaultAgentSkillFactory
import com.easy.easyai.skills.command.*
import com.easy.easyai.skills.selection.SkillSelectionClient
import com.easy.easyai.skills.selection.SkillTurnRouter
import com.easy.easyai.storage.local.LocalDirObjectStorage
import com.easy.easyai.tools.SpringToolFactory
import com.easy.easyai.tools.web.IntegrationConfig
import io.micrometer.observation.ObservationRegistry
import jakarta.annotation.PostConstruct
import java.nio.file.Path
import com.easy.easyai.api.llm.ChatModel
import org.springframework.beans.factory.InitializingBean
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Lazy
import org.slf4j.LoggerFactory

@AutoConfiguration
@ComponentScan(basePackages = ["com.easy.easyai.core", "com.easy.easyai.agent", "com.easy.easyai.tools", "com.easy.easyai.skills", "com.easy.easyai.repository"])
@EnableConfigurationProperties(EasyAiProperties::class, IntegrationProperties::class)
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

    /**
     * Installs the deployment-wide STATIC integration config from `easyai.integrations.*` before any
     * request or tool build reads it. A no-op when nothing is pinned, which leaves the file /
     * environment-variable resolution in charge (desktop / single-tenant).
     */
    @Bean
    open fun integrationStaticOverrideInitializer(
        integrationProperties: IntegrationProperties
    ): InitializingBean = InitializingBean {
        if (integrationProperties.isStatic()) {
            IntegrationConfig.setStaticOverride(
                IntegrationConfig(
                    exaApiKey = integrationProperties.exaApiKey.ifBlank { null },
                    parallelApiKey = integrationProperties.parallelApiKey.ifBlank { null },
                    websearchProvider = integrationProperties.websearchProvider.ifBlank { null }
                )
            )
        }
    }

    @Bean
    @ConditionalOnMissingBean
    open fun messageConverter(
        objectStorageResolver: ObjectProvider<ObjectStorageResolver>,
        builders: List<ToolBuilder>
    ): MessageConverter =
        DefaultMessageConverter(
            objectStorageResolver = objectStorageResolver.getIfAvailable(),
            contextProjectors = ToolContextProjector.registryFrom(builders)
        )

    @Bean
    @ConditionalOnMissingBean
    open fun toolExecutionEngine(properties: EasyAiProperties): ToolExecutionEngine =
        DefaultToolExecutionEngine()

    @Bean
    @ConditionalOnMissingBean
    open fun toolFactory(builders: List<ToolBuilder>): ToolFactory = SpringToolFactory(builders)

    // ========== Auxiliary Model Beans ==========

    /**
     * Resolves a per-user configured model for a background [AuxModelTask] (e.g. compaction).
     * Both the settings store (repository, R2DBC-gated) and the config store are optional: when
     * either is absent the resolver simply yields null, so every consumer falls back to its own
     * default (for compaction, the chat-session model).
     */
    @Bean
    @ConditionalOnMissingBean
    open fun auxModelResolver(
        @Autowired(required = false) settingsStore: AuxModelSettingsStore? = null,
        @Autowired(required = false) configStore: ModelProviderConfigStore? = null,
        chatModelFactories: List<ChatModelFactory>
    ): AuxModelResolver = DefaultAuxModelResolver(settingsStore, configStore, chatModelFactories)

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

    /**
     * Registry-facing view of `easyai.skills.*` as a bean, so collaborators outside this class
     * (e.g. [com.easy.easyai.skills.SkillToolBuilder]) resolve the owner root the same way the
     * registry does instead of re-deriving from properties.
     */
    @Bean
    @ConditionalOnMissingBean
    open fun skillConfig(): SkillConfig = SkillConfig(
        enabled = properties.skills.enabled,
        rootDir = properties.skills.rootDir,
        injectIntoSystemPrompt = properties.skills.injectIntoSystemPrompt,
        packageMaxBytes = properties.skills.packageMaxBytes,
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillDiscovery(): SkillDiscovery = DefaultSkillDiscovery()

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillRegistry(discovery: SkillDiscovery, skillConfig: SkillConfig): SkillRegistry =
        DefaultSkillRegistry(discovery, skillConfig)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillAccessResolver(
        skillRegistry: SkillRegistry,
        catalog: ObjectProvider<AsyncSkillCatalogStore>,
    ): SkillAccessResolver = SkillAccessResolver(skillRegistry, catalog.getIfAvailable())

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun agentSkillFactory(): AgentSkillFactory = DefaultAgentSkillFactory()

    // ========== Skill Package / Sync Beans ==========

    /**
     * Skill packages are stored per owner in the same object storage chat attachments use. With no
     * row configured for anybody, the zip tree falls back to a directory under the skill root, so
     * desktop and single-machine deployments keep full restore/push semantics without an OSS bucket.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillPackageStore(
        storageResolver: ObjectProvider<ObjectStorageResolver>,
        skillConfig: SkillConfig,
    ): SkillPackageStore = SkillPackageStore(
        resolver = storageResolver.getIfAvailable(),
        localFallback = LocalDirObjectStorage(Path.of(skillConfig.rootDir, PACKAGE_FALLBACK_DIR)),
    )

    /**
     * The single write direction of the pipeline: catalog row + object-storage package + local
     * directory converge here. Present whenever skills are enabled — retrieval indexing is optional
     * on top, restoring a user's skills to disk is not.
     *
     * Without an [AsyncSkillCatalogStore] (r2dbc off) it degrades to rescanning the roots from disk.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillSyncService(
        catalog: ObjectProvider<AsyncSkillCatalogStore>,
        registry: ObjectProvider<SkillRegistry>,
        discovery: SkillDiscovery,
        packages: SkillPackageStore,
        skillConfig: SkillConfig,
    ): SkillSyncService = SkillSyncService(
        catalog = catalog.getIfAvailable(),
        registry = registry.getIfAvailable(),
        discovery = discovery,
        packages = packages,
        config = skillConfig,
    )

    // ========== Skill Prompt / RAG Beans ==========

    /**
     * Not RAG-gated: it also carries the `enabled` filtering that must apply while the full list is
     * still injected, so both prompt paths (default agent and DB sessions) share one decision.
     *
     * [SkillPromptSource.firstAccessSync] is the lazy gate a request hits before its first read, so
     * a user who signs in on a machine that has never synced their skills sees them anyway.
     */
    @Bean
    @ConditionalOnMissingBean
    open fun skillPromptSource(
        @Autowired(required = false) skillRegistry: SkillRegistry? = null,
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
        @Autowired(required = false) skillStore: SkillStore? = null,
        skillConfig: SkillConfig,
        refreshService: ObjectProvider<SkillRefreshService>,
    ): SkillPromptSource = SkillPromptSource(
        registry = skillRegistry,
        catalog = catalog,
        injectIntoSystemPrompt = skillConfig.injectIntoSystemPrompt,
        ragEnabled = properties.skills.rag.enabled,
        // Discovery is only "ready" when the whole chain exists. RagAutoConfiguration hands out a
        // SkillStore whenever the flag is on, but suppressing the prompt listing needs rows for that
        // store to have been built from. Without this conjunction, `rag on + r2dbc off` would
        // suppress the listing while skill_search returns empty forever — the worst of both worlds.
        ragDiscoveryReady = skillStore != null && catalog != null,
        firstAccessSync = { owners -> refreshService.ifAvailable?.ensureSyncedOwners(owners) },
        directInjectMaxSkills = properties.skills.directInjectMaxCount
    )

    /**
     * Decision-model transport for skill routing. Cheap to keep registered: without a
     * `SKILL_SELECTION` aux model row the router never fires an HTTP call.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["selection.enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillSelectionClient(): SkillSelectionClient = SkillSelectionClient.http()

    /**
     * Per-message skill routing consumer. Absent when skills or selection are off, or when the
     * decision client bean was not created — the chat chain treats a missing router as "no routing".
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(SkillSelectionClient::class)
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillTurnRouter(
        promptSource: SkillPromptSource,
        auxResolver: AuxModelResolver,
        client: SkillSelectionClient,
    ): SkillTurnRouter = SkillTurnRouter(
        promptSource = promptSource,
        auxResolver = auxResolver,
        client = client,
        minConfidence = properties.skills.selection.minConfidence,
        timeoutMs = properties.skills.selection.timeoutMs
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillIndexer(
        syncService: SkillSyncService,
        @Autowired(required = false) skillStore: SkillStore? = null,
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
    ): SkillIndexer = SkillIndexer(
        skillStore = skillStore,
        catalog = catalog,
        syncService = syncService,
        indexConcurrency = properties.skills.rag.indexConcurrency
    )

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillCatalogService(
        indexer: SkillIndexer,
        @Autowired(required = false) catalog: AsyncSkillCatalogStore? = null,
    ): SkillCatalogService = SkillCatalogService(
        catalog = catalog,
        indexer = indexer
    )

    /**
     * The one refresh chain — reconcile catalog/package/disk, then project the rows into the
     * retrieval index — shared by the startup pass, the login hook and the `refresh_skills` tool.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillRefreshService(
        syncService: SkillSyncService,
        indexer: SkillIndexer,
    ): SkillRefreshService = SkillRefreshService(syncService, indexer)

    /**
     * Thin event shell over [SkillRefreshService]: no skills, no runner. Owners come from the
     * catalog, never from a guess about who is logged in.
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = SKILL_PREFIX, name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun skillIndexStartupRunner(
        refreshService: SkillRefreshService,
        catalog: ObjectProvider<AsyncSkillCatalogStore>,
    ): SkillIndexStartupRunner = SkillIndexStartupRunner(refreshService, catalog.getIfAvailable())

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
        @Autowired(required = false) promptProvider: McpPromptProvider? = null,
        @Autowired(required = false) userCommandStore: AsyncUserCommandStore? = null,
        @Autowired(required = false) builtinHandlers: List<BuiltinCommandHandler>? = null,
    ): CommandService = CommandService(
        commandRegistry, promptProvider, userCommandStore, builtinHandlers ?: emptyList(),
        skillAccessResolver.getIfAvailable()
    )

    @Bean
    @ConditionalOnMissingBean(AgentService::class)
    open fun agentService(
        chatModelFactories: List<ChatModelFactory>,
        // Optional: models are resolved per session from a ModelProviderConfig through the
        // factories. A host may still register a ChatModel bean to serve as the fallback for
        // contexts that carry no config (Agent.chatModel fails with a clear error otherwise).
        @Autowired(required = false)
        chatModel: ChatModel?,
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

    companion object {
        /** Master switch of the skill subsystem (prompt, registry, sync, retrieval on top). */
        private const val SKILL_PREFIX = "easyai.skills"

        /** Directory under the skill root holding packages when no object storage is configured. */
        private const val PACKAGE_FALLBACK_DIR = ".packages"
    }
}
