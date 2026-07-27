package com.easy.easyai.autoconfigure.web

import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.api.config.ChatModelFactory
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.UserStore
import com.easy.easyai.auth.jwt.JwtTokenProvider
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.agent.TransformContextService
import com.easy.easyai.core.agent.WaitForUserListener
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.tool.ScriptEnvProvider
import com.easy.easyai.core.message.DefaultMessageConverter
import com.easy.easyai.core.message.MessageConverter
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.core.team.TeamExecutionStore
import com.easy.easyai.skills.team.TeamCoordinationStateRegistry
import com.easy.easyai.snapshot.GitSnapshotService
import com.easy.easyai.snapshot.RevertService
import com.easy.easyai.snapshot.SnapshotEventListener
import com.easy.easyai.snapshot.SnapshotService
import com.easy.easyai.skills.command.CommandService
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.web.controller.ChatController
import com.easy.easyai.web.handler.CheckpointCustomEventConverter
import com.easy.easyai.web.handler.CustomEventConverter
import com.easy.easyai.web.handler.GoalStatusCustomEventConverter
import com.easy.easyai.web.security.AuthProperties
import com.easy.easyai.web.service.ScriptLlmProperties
import com.easy.easyai.web.security.AuthService
import com.easy.easyai.web.security.McpPreConnectFilter
import com.easy.easyai.web.service.ConfigValidator
import com.easy.easyai.web.service.GoalPauseListener
import com.easy.easyai.web.service.ChatStreamService
import com.easy.easyai.web.service.FileStorageService
import com.easy.easyai.web.service.GoalCommandHandler
import com.easy.easyai.web.service.SessionService
import com.easy.easyai.web.service.configgen.AgentBasedConfigGenerator
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.SmartInitializingSingleton
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
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
@AutoConfiguration
@ConditionalOnClass(ChatModel::class)
@ConditionalOnProperty(prefix = "easyai.web", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easyai.database", name = ["configured"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WebProperties::class, AuthProperties::class, ScriptLlmProperties::class)
@ComponentScan(basePackages = ["com.easy.easyai.web"])
open class WebAutoConfiguration {

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
        scriptEnvProvider: ScriptEnvProvider? = null
    ): ChatStreamService {
        return ChatStreamService(sessionManager, configStore, modelFactories,
            transformContextService, permissionService, sessionStore, projectStore, snapshotService,
            customEventConverters ?: emptyList(), commandService, goalStatusNotifier, goalStore, fileStorageService,
            scriptEnvProvider)
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
        @Value("\${easyai.data-dir:\${user.home}/.easyai}") dataDir: String
    ): FileStorageService {
        return FileStorageService(dataDir)
    }

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
        teamExecutionStore: TeamExecutionStore? = null
    ): SessionService {
        return SessionService(sessionManager, sessionStore, snapshotService, fileStorageService, teamStateRegistry, teamExecutionStore)
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

    // ─── AI Config Generation Beans ────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(ConfigValidator::class)
    open fun configValidator(
        toolRegistry: ToolRegistry,
        agentStore: AsyncAgentStore,
        @Autowired(required = false) skillRegistry: SkillRegistry? = null,
        @Autowired(required = false) mcpClientManager: McpClientManager? = null,
        @Autowired(required = false) templateRenderer: TemplateRenderer? = null,
    ): ConfigValidator {
        return ConfigValidator.forLegacy(
            toolRegistry = toolRegistry,
            agentStore = agentStore,
            objectMapper = com.easy.easyai.common.util.SharedObjectMapper.instance,
            skillRegistry = skillRegistry,
            mcpClientManager = mcpClientManager,
            templateRenderer = templateRenderer,
        )
    }

    // ─── Auth Beans ──────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(AuthService::class)
    open fun authService(
        userStore: UserStore,
        refreshTokenStore: RefreshTokenStore,
        jwtTokenProvider: JwtTokenProvider,
        authProperties: AuthProperties
    ): AuthService {
        return AuthService(userStore, refreshTokenStore, jwtTokenProvider, authProperties)
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
        @Autowired(required = false) skillRegistry: SkillRegistry? = null,
        @Autowired(required = false) mcpClientManager: McpClientManager? = null,
    ): AgentBasedConfigGenerator {
        return AgentBasedConfigGenerator(
            agentService = agentService,
            configValidator = configValidator,
            toolRegistry = toolRegistry,
            agentStore = agentStore,
            skillRegistry = skillRegistry,
            mcpClientManager = mcpClientManager,
            modelConfigStore = modelConfigStore,
        )
    }
}
