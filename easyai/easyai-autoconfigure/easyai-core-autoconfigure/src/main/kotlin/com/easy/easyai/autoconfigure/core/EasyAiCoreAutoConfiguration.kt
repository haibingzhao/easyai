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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.ComponentScan
import org.springframework.context.annotation.Lazy
import java.nio.file.Path

@AutoConfiguration
@ComponentScan(basePackages = ["com.easy.easyai.core", "com.easy.easyai.agent", "com.easy.easyai.tools", "com.easy.easyai.skills", "com.easy.easyai.repository"])
@EnableConfigurationProperties(EasyAiProperties::class)
open class EasyAiCoreAutoConfiguration(
    private val properties: EasyAiProperties
) {

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
    open fun messageConverter(): MessageConverter = DefaultMessageConverter()

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
        val config = SkillConfig(
            enabled = properties.skills.enabled,
            paths = properties.skills.paths,
            homeSkillDirs = properties.skills.homeSkillDirs,
            injectIntoSystemPrompt = properties.skills.injectIntoSystemPrompt,
            systemPromptFormat = properties.skills.systemPromptFormat,
            workDir = properties.workDir,
        )
        return DefaultSkillRegistry(discovery, config)
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.skills", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun agentSkillFactory(): AgentSkillFactory = DefaultAgentSkillFactory()

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
        @Autowired(required = false) skillRegistry: SkillRegistry? = null,
        @Autowired(required = false) promptProvider: McpPromptProvider? = null,
        @Autowired(required = false) builtinHandlers: List<BuiltinCommandHandler>? = null,
    ): CommandRegistry {
        return DefaultCommandRegistry(skillRegistry, promptProvider, builtinHandlers ?: emptyList())
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "easyai.commands", name = ["enabled"], havingValue = "true", matchIfMissing = true)
    open fun commandService(
        commandRegistry: CommandRegistry,
        @Autowired(required = false) promptProvider: McpPromptProvider? = null,
        @Autowired(required = false) userCommandStore: AsyncUserCommandStore? = null,
        @Autowired(required = false) builtinHandlers: List<BuiltinCommandHandler>? = null,
    ): CommandService = CommandService(commandRegistry, promptProvider, userCommandStore, builtinHandlers ?: emptyList())

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
            waitForUserListener = waitForUserListener,
            outputSchemaValidator = outputSchemaValidator
        )
    }

    @Bean
    @ConditionalOnMissingBean(Agent::class)
    open fun agent(
        agentService: AgentService,
        properties: EasyAiProperties,
        skillRegistry: SkillRegistry?,
        toolFactory: ToolFactory
    ): Agent {
        val context = AgentContext(
            agentId = "default-agent",
            projectPath = Path.of(properties.workDir)
        )
        // All tools (including SkillTool) are created uniformly via ToolBuilder pattern
        val allTools = toolFactory.createTools(context, agentService)

        // Build skills data for prompt rendering (not pre-built into a string)
        val skillsData = if (properties.skills.injectIntoSystemPrompt && skillRegistry != null) {
            skillRegistry.all()
                .filter { !it.description.isNullOrBlank() }
                .map { mapOf<String, Any?>("name" to it.name, "description" to it.description) }
        } else {
            emptyList()
        }

        return Agent(
            context = AgentContext(
                agentId = "default-agent",
                customInstructions = properties.systemPrompt,
                skills = skillsData,
                tools = allTools,
                maxIterations = properties.maxIterations,
                maxRetries = properties.maxRetries
            ),
            services = agentService
        )
    }
}
