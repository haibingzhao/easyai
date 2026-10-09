package com.easy.easyai.autoconfigure.web

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.group.AccessTokenClaimsContributor
import com.easy.easyai.auth.group.NoopClaimsContributor
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.core.permission.PermissionRuleStore
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.storage.ObjectStorage
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.core.tool.ScriptEnvProvider
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.SessionExecutionService
import com.easy.easyai.skills.SkillAccessResolver
import com.easy.easyai.skills.command.CommandService
import com.easy.easyai.skills.selection.SkillTurnRouter
import com.easy.easyai.skills.team.TeamCoordinationStateRegistry
import com.easy.easyai.snapshot.GitSnapshotService
import com.easy.easyai.snapshot.RevertService
import com.easy.easyai.snapshot.SnapshotEventListener
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.tools.background.BackgroundTaskManagerRegistry
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.controller.ChatController
import com.easy.easyai.web.handler.BackgroundTaskCustomEventConverter
import com.easy.easyai.web.handler.CheckpointCustomEventConverter
import com.easy.easyai.web.handler.CustomEventConverter
import com.easy.easyai.web.handler.GoalStatusCustomEventConverter
import com.easy.easyai.web.security.AuthProperties
import com.easy.easyai.web.security.AuthService
import com.easy.easyai.web.security.McpPreConnectFilter
import com.easy.easyai.web.service.*
import com.easy.easyai.web.service.configgen.AgentBasedConfigGenerator
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import java.nio.file.Path

/**
 * Auto-configuration for EasyAI Web module.
 *
 * Creates beans for:
 * - [SessionManager] - manages conversation sessions (fallback to InMemorySessionManager if DatabaseSessionManager not available)
 * - [ChatStreamService] - bridges Agent with SSE streaming
 * - [ChatController] - exposes REST/SSE endpoints
 * - [SnapshotEventListener] - global agent event listener for file checkpoint creation
 * - [CheckpointCustomEventConverter] - converts checkpoint CustomEvents to SSE events
 *
 * Note: [ModelProviderConfigStore] is provided by easyai-r2dbc-autoconfigure.
 * Note: [com.easy.easyai.repository.session.DatabaseSessionManager] is provided by easyai-r2dbc-autoconfigure and takes precedence.
 *
 * Enabled by default when:
 * - spring-boot-starter-web is on the classpath
 * - easyai.web.enabled=true (or not set)
 */
@AutoConfiguration(afterName = ["com.easy.easyai.autoconfigure.r2dbc.R2dbcRepositoryAutoConfiguration"])
@ConditionalOnClass(ChatModel::class)
@ConditionalOnProperty(prefix = "easyai.web", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easyai.database", name = ["configured"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WebProperties::class, AuthProperties::class, ScriptLlmProperties::class)
@ComponentScan(basePackages = ["com.easy.easyai.web"])
open class WebAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(AsyncProjectStore::class)
    open fun defaultWorkspaceService(
        projectStore: AsyncProjectStore,
        @Autowired(required = false)
        sessionStore: AsyncSessionStore? = null,
        @Autowired(required = false)
        permissionRuleStore: PermissionRuleStore? = null,
        @Autowired(required = false)
        snapshotService: SnapshotService? = null,
        @Value("\${easyai.data-dir:\${user.home}/.easyai}") dataDir: String
    ): DefaultWorkspaceService =
        DefaultWorkspaceService(projectStore, sessionStore, permissionRuleStore, snapshotService, dataDir)

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(DefaultWorkspaceService::class)
    open fun tempWorkspaceSweepRunner(
        defaultWorkspaceService: DefaultWorkspaceService
    ): TempWorkspaceSweepRunner = TempWorkspaceSweepRunner(defaultWorkspaceService)

    @Bean
    @ConditionalOnMissingBean
    open fun chatStreamService(
        sessionManager: SessionManager,
        configStore: ModelProviderConfigStore,
        modelFactories: List<ChatModelFactory>,
        @Autowired(required = false)
        transformContextService: TransformContextService? = null,
        @Autowired(required = false)
        permissionService: PermissionService? = null,
        @Autowired(required = false)
        sessionStore: AsyncSessionStore? = null,
        @Autowired(required = false)
        projectStore: AsyncProjectStore? = null,
        @Autowired(required = false)
        snapshotService: SnapshotService? = null,
        @Autowired(required = false)
        customEventConverters: List<CustomEventConverter>? = emptyList(),
        @Autowired(required = false)
        commandService: CommandService? = null,
        @Autowired(required = false)
        goalStatusNotifier: GoalStatusNotifier? = null,
        @Autowired(required = false)
        goalStore: GoalStore? = null,
        @Autowired(required = false)
        fileStorageService: FileStorageService? = null,
        @Autowired(required = false)
        scriptEnvProvider: ScriptEnvProvider? = null,
        @Autowired(required = false)
        executionService: SessionExecutionService? = null,
        @Autowired(required = false)
        skillTurnRouter: SkillTurnRouter? = null,
        @Autowired(required = false)
        backgroundTaskManagerRegistry: BackgroundTaskManagerRegistry? = null,
        @Autowired(required = false)
        defaultWorkspaceService: DefaultWorkspaceService? = null
    ): ChatStreamService {
        return ChatStreamService(sessionManager, configStore, modelFactories,
            transformContextService, permissionService, sessionStore, projectStore, snapshotService,
            customEventConverters ?: emptyList(), commandService, goalStatusNotifier, goalStore, fileStorageService,
            scriptEnvProvider, executionService, skillTurnRouter, backgroundTaskManagerRegistry, defaultWorkspaceService)
    }

    @Bean
    @ConditionalOnMissingBean(SessionExecutionService::class)
    open fun sessionExecutionService(
        @Autowired(required = false) sessionStore: AsyncSessionStore? = null
    ): SessionExecutionService {
        return SessionExecutionService(sessionStore)
    }

    @Bean
    @ConditionalOnMissingBean(GoalCommandHandler::class)
    open fun goalCommandHandler(
        goalStore: GoalStore,
        @Autowired(required = false)
        goalStatusNotifier: GoalStatusNotifier? = null
    ): GoalCommandHandler {
        return GoalCommandHandler(goalStore, goalStatusNotifier)
    }

    @Bean
    @ConditionalOnMissingBean(WaitForUserListener::class)
    open fun waitForUserListener(
        goalStore: GoalStore,
        @Autowired(required = false)
        goalStatusNotifier: GoalStatusNotifier? = null
    ): WaitForUserListener {
        return GoalPauseListener(goalStore, goalStatusNotifier)
    }

    @Bean
    @ConditionalOnMissingBean
    open fun fileStorageService(
        @Value($$"${easyai.data-dir:${user.home}/.easyai}") dataDir: String,
        objectStorageResolver: ObjectProvider<ObjectStorageResolver>
    ): FileStorageService {
        return FileStorageService(dataDir, objectStorageResolver.ifAvailable)
    }

    /**
     * Profile pictures share the two media tiers — the caller's own object storage, else the
     * deployment-local media directory — but live in a personal `avatars/` namespace that needs no session,
     * because only their owner ever reads them back.
     */
    @Bean
    @ConditionalOnMissingBean
    open fun avatarStorageService(
        objectStorageResolver: ObjectProvider<ObjectStorageResolver>,
        @Autowired(required = false)
        @Qualifier("localMediaObjectStorage")
        localStorage: ObjectStorage? = null
    ): AvatarStorageService = AvatarStorageService(objectStorageResolver.ifAvailable, localStorage)

    /**
     * After all singletons are created, configure the [DefaultMessageConverter]'s allowed base directory
     * to [FileStorageService.imagesRoot] — preventing FileRefContent from reading arbitrary file paths.
     */
    @Bean
    open fun messageConverterSecurityConfigurer(
        messageConverter: MessageConverter,
        @Autowired(required = false) fileStorageService: FileStorageService? = null
    ): SmartInitializingSingleton {
        return SmartInitializingSingleton {
            if (messageConverter is DefaultMessageConverter && fileStorageService != null) {
                messageConverter.allowedBaseDir = fileStorageService.imagesRoot
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    open fun sessionService(
        sessionManager: SessionManager,
        sessionStore: AsyncSessionStore,
        @Autowired(required = false)
        snapshotService: SnapshotService? = null,
        @Autowired(required = false)
        fileStorageService: FileStorageService? = null,
        @Autowired(required = false)
        teamStateRegistry: TeamCoordinationStateRegistry? = null,
        @Autowired(required = false)
        teamExecutionStore: TeamExecutionStore? = null,
        @Autowired(required = false)
        configStore: ModelProviderConfigStore? = null,
        @Autowired(required = false)
        backgroundTaskManagerRegistry: BackgroundTaskManagerRegistry? = null,
        @Autowired(required = false)
        defaultWorkspaceService: DefaultWorkspaceService? = null
    ): SessionService {
        return SessionService(sessionManager, sessionStore, snapshotService, fileStorageService, teamStateRegistry, teamExecutionStore, configStore, backgroundTaskManagerRegistry, defaultWorkspaceService)
    }

    @Bean
    @ConditionalOnMissingBean(SnapshotService::class)
    open fun snapshotService(
        @Value("\${easyai.data-dir:\${user.home}/.easyai}") dataDir: String
    ): SnapshotService {
        return GitSnapshotService(Path.of(dataDir))
    }

    @Bean
    @ConditionalOnMissingBean(RevertService::class)
    open fun revertService(
        snapshotService: SnapshotService
    ): RevertService {
        return RevertService(snapshotService)
    }

    @Bean
    @ConditionalOnMissingBean(SnapshotEventListener::class)
    open fun snapshotEventListener(
        @Autowired(required = false) snapshotService: SnapshotService?,
        @Autowired(required = false) teamExecutionStore: TeamExecutionStore?
    ): SnapshotEventListener? {
        if (snapshotService == null) return null
        return SnapshotEventListener(snapshotService, teamExecutionStore)
    }

    @Bean
    @ConditionalOnMissingBean(CheckpointCustomEventConverter::class)
    open fun checkpointCustomEventConverter(): CustomEventConverter {
        return CheckpointCustomEventConverter()
    }

    @Bean
    @ConditionalOnMissingBean(GoalStatusCustomEventConverter::class)
    open fun goalStatusCustomEventConverter(): CustomEventConverter {
        return GoalStatusCustomEventConverter()
    }

    @Bean
    @ConditionalOnMissingBean(BackgroundTaskCustomEventConverter::class)
    open fun backgroundTaskCustomEventConverter(): CustomEventConverter {
        return BackgroundTaskCustomEventConverter()
    }

    // ─── AI Config Generation Beans ────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(ConfigValidator::class)
    open fun configValidator(
        toolRegistry: ToolRegistry,
        agentStore: AsyncAgentStore,
        toolFactory: ToolFactory,
        @Autowired(required = false) skillAccessResolver: SkillAccessResolver? = null,
        @Autowired(required = false) mcpClientManager: McpClientManager? = null,
        @Autowired(required = false) templateRenderer: TemplateRenderer? = null,
    ): ConfigValidator {
        return ConfigValidator.forLegacy(
            toolRegistry = toolRegistry,
            agentStore = agentStore,
            objectMapper = com.easy.easyai.common.util.SharedObjectMapper.instance,
            toolFactory = toolFactory,
            skillAccessResolver = skillAccessResolver,
            mcpClientManager = mcpClientManager,
            templateRenderer = templateRenderer,
        )
    }

    // ─── Auth Beans ──────────────────────────────────────────────────────────────

    /**
     * Default group-claims contributor: no group sharing, so a login's only owner is itself and the
     * runtime read filter stays `{self, system}`. A product that adds group sharing registers its own
     * [AccessTokenClaimsContributor] bean, which this backs off for.
     */
    @Bean
    @ConditionalOnMissingBean(AccessTokenClaimsContributor::class)
    open fun accessTokenClaimsContributor(): AccessTokenClaimsContributor = NoopClaimsContributor

    @Bean
    @ConditionalOnMissingBean(AuthService::class)
    open fun authService(
        userStore: UserStore,
        refreshTokenStore: RefreshTokenStore,
        jwtTokenProvider: JwtTokenProvider,
        authProperties: AuthProperties,
        claimsContributor: AccessTokenClaimsContributor
    ): AuthService {
        return AuthService(userStore, refreshTokenStore, jwtTokenProvider, authProperties, claimsContributor)
    }

    @Bean
    open fun mcpPreConnectFilter(
        jwtTokenProvider: JwtTokenProvider,
        authProperties: AuthProperties,
        @Autowired(required = false) mcpClientManager: McpClientManager? = null
    ): McpPreConnectFilter? {
        if (mcpClientManager == null) return null
        return McpPreConnectFilter(mcpClientManager, jwtTokenProvider, authProperties.enabled)
    }

    @Bean
    @ConditionalOnMissingBean(AgentBasedConfigGenerator::class)
    open fun agentBasedConfigGenerator(
        agentService: AgentService,
        configValidator: ConfigValidator,
        toolRegistry: ToolRegistry,
        agentStore: AsyncAgentStore,
        modelConfigStore: ModelProviderConfigStore,
        toolFactory: ToolFactory,
        @Autowired(required = false) skillAccessResolver: SkillAccessResolver? = null,
        @Autowired(required = false) mcpClientManager: McpClientManager? = null,
    ): AgentBasedConfigGenerator {
        return AgentBasedConfigGenerator(
            agentService = agentService,
            configValidator = configValidator,
            toolRegistry = toolRegistry,
            agentStore = agentStore,
            skillAccessResolver = skillAccessResolver,
            mcpClientManager = mcpClientManager,
            modelConfigStore = modelConfigStore,
            toolFactory = toolFactory,
        )
    }
}
