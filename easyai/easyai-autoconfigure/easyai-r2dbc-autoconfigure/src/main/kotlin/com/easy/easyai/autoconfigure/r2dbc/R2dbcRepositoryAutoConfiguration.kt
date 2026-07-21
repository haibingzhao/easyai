package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.api.config.DefaultModelConfigService
import com.easy.easyai.api.config.ModelConfigGroupStore
import com.easy.easyai.api.config.ModelConfigService
import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.auth.RefreshTokenStore
import com.easy.easyai.auth.UserStore
import com.easy.easyai.autoconfigure.core.EasyAiProperties
import com.easy.easyai.compaction.CompactionListener
import com.easy.easyai.compaction.OriginalMessageLoader
import com.easy.easyai.core.agent.*
import com.easy.easyai.core.command.AsyncUserCommandStore
import com.easy.easyai.core.goal.GoalCompletionCheck
import com.easy.easyai.core.goal.GoalStatusNotifier
import com.easy.easyai.core.goal.GoalStore
import com.easy.easyai.core.permission.PermissionRuleStore
import com.easy.easyai.core.permission.PermissionService
import com.easy.easyai.core.prompt.InstructionsLoader
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.repository.agent.R2dbcAgentStore
import com.easy.easyai.skills.command.BuiltinCommandHandler
import com.easy.easyai.repository.auth.R2dbcRefreshTokenStore
import com.easy.easyai.repository.command.R2dbcAsyncUserCommandStore
import com.easy.easyai.repository.config.R2dbcModelConfigGroupStore
import com.easy.easyai.repository.config.R2dbcModelConfigStore
import com.easy.easyai.repository.database.DatabaseMigration
import com.easy.easyai.repository.goal.SqlGoalStore
import com.easy.easyai.repository.mcp.R2dbcMcpServerStore
import com.easy.easyai.repository.permission.R2dbcAsyncPermissionRuleStore
import com.easy.easyai.repository.project.AsyncProjectStore
import com.easy.easyai.repository.project.R2dbcAsyncProjectStore
import com.easy.easyai.repository.session.*
import com.easy.easyai.repository.swarm.R2dbcSwarmPresetStore
import com.easy.easyai.repository.swarm.R2dbcSwarmRunStore
import com.easy.easyai.repository.todo.AsyncTodoStore
import com.easy.easyai.repository.todo.R2dbcAsyncTodoStore
import com.easy.easyai.repository.todo.TodoCompletionCheck
import com.easy.easyai.repository.user.R2dbcUserStore
import com.easy.easyai.swarm.preset.SwarmPresetStore
import com.easy.easyai.swarm.store.SwarmRunStore
import com.easy.easyai.tools.mcp.AsyncMcpServerStore
import com.easy.easyai.tools.mcp.McpClientManager
import com.easy.easyai.tools.mcp.McpToolProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Lazy
import org.springframework.core.annotation.Order

/**
 * Auto-Configuration for async R2DBC-based repository layer.
 *
 * Activated when `easyai.r2dbc.enabled=true` (default).
 * Supports any R2DBC-compatible database (H2, PostgreSQL, etc.).
 */
@AutoConfiguration
@EnableConfigurationProperties(value = [R2dbcProperties::class, EasyAiProperties::class])
@ConditionalOnProperty(prefix = "easyai.r2dbc", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class R2dbcRepositoryAutoConfiguration(
    private val r2dbcProperties: R2dbcProperties,
    private val easyAiProperties: EasyAiProperties
) {
    private fun buildSkillsData(skillRegistry: com.easy.easyai.skills.SkillRegistry?): List<Map<String, Any?>> {
        return if (easyAiProperties.skills.injectIntoSystemPrompt) {
            skillRegistry?.all()
                ?.filter { !it.description.isNullOrBlank() }
                ?.map { mapOf<String, Any?>("name" to it.name, "description" to it.description) }
                ?: emptyList()
        } else {
            emptyList()
        }
    }
    @Bean
    fun databaseMigration(): DatabaseMigration = DatabaseMigration.defaultTables()

    @Bean
    fun flywayMigrationRunner(): FlywayMigrationRunner? {
        return if (r2dbcProperties.flywayEnabled) FlywayMigrationRunner(r2dbcProperties) else null
    }

    @Bean
    fun r2dbcDatabaseInitializer(
        migration: DatabaseMigration,
        flywayRunner: FlywayMigrationRunner?
    ): R2dbcDatabaseInitializer {
        return R2dbcDatabaseInitializer(r2dbcProperties, migration, flywayRunner)
    }

    @Bean
    fun agentSeedInitializer(
        agentStore: AsyncAgentStore,
        initializer: R2dbcDatabaseInitializer,
        toolFactory: ToolFactory,
        @Autowired(required = false) builtinCommandHandlers: List<BuiltinCommandHandler>? = null
    ): AgentSeedInitializer {
        return AgentSeedInitializer(agentStore, initializer, toolFactory, builtinCommandHandlers ?: emptyList())
    }

    @Bean
    @ConditionalOnMissingBean(AsyncSessionStore::class)
    fun asyncSessionStore(initializer: R2dbcDatabaseInitializer): AsyncSessionStore {
        return R2dbcAsyncSessionStore(
            db = initializer.getDatabase()
        )
    }
    @Bean
    @ConditionalOnMissingBean(CompactionListener::class)
    fun compactionListener(sessionStore: R2dbcAsyncSessionStore): CompactionListener {
        return R2dbcCompactionListener(sessionStore)
    }

    @Bean
    @ConditionalOnMissingBean(OriginalMessageLoader::class)
    fun originalMessageLoader(sessionStore: R2dbcAsyncSessionStore): OriginalMessageLoader {
        return R2dbcOriginalMessageLoader(sessionStore)
    }

    @Bean
    @ConditionalOnMissingBean(SessionToolResolver::class)
    fun sessionToolResolver(
        @Lazy toolFactory: ToolFactory,
        agentService: AgentService,
        agentStore: AsyncAgentStore,
        @Autowired(required = false) projectStore: AsyncProjectStore? = null,
        @Autowired(required = false) mcpToolProvider: McpToolProvider? = null
    ): SessionToolResolver {
        return SessionToolResolver(
            projectStore = projectStore,
            toolFactory = toolFactory,
            agentService = agentService,
            mcpToolProvider = mcpToolProvider,
            agentStore = agentStore
        )
    }

    @Bean
    @ConditionalOnMissingBean(SubAgentContextResolver::class)
    fun subAgentContextResolver(
        sessionToolResolver: SessionToolResolver,
        agentStore: AsyncAgentStore,
        @Autowired(required = false) skillRegistry: com.easy.easyai.skills.SkillRegistry? = null
    ): SubAgentContextResolver {
        val skillsData = buildSkillsData(skillRegistry)
        return object : SubAgentContextResolver {
            override suspend fun resolve(
                agentDef: AgentDefinition,
                parentContext: AgentContext
            ): Pair<AgentContext, List<ToolDefinition>> {
                // Resolve skills with whitelist filtering (same logic as DatabaseSessionManager.resolveSkillsForAgent)
                val allowedSkillConfigs = agentStore.getAgentToolConfigs(agentDef.id, TargetType.SKILL)
                val (agentSkills, allowedSkillNames) = if (allowedSkillConfigs.isEmpty()) {
                    listOf<Map<String, Any?>>() to listOf()  // No whitelist = no skills
                } else {
                    val allowedNames = allowedSkillConfigs.map { it.targetName }
                    val allowedSet = allowedNames.toSet()
                    skillsData.filter { (it["name"] as? String) in allowedSet } to allowedNames
                }
                // Resolve instructions based on sub-agent's own instructionsEnabled flag
                val instructions = if (agentDef.instructionsEnabled) {
                    parentContext.instructions.ifEmpty { InstructionsLoader.load(parentContext.projectPath) }
                } else emptyList()
                // Build base context with sub-agent's own identity and resolved configs.
                // parentAgentId is set here so SubAgentToolBuilder.build() detects this is a sub-agent
                // context and returns null — preventing the SubAgentTool from being created (recursion guard).
                val baseContext = parentContext.copy(
                    agentId = agentDef.id,
                    parentAgentId = parentContext.parentAgentId ?: parentContext.agentId,
                    promptTemplate = agentDef.promptTemplate,
                    customInstructions = agentDef.customInstructions,
                    subAgents = emptyList(),
                    skills = agentSkills,
                    allowedSkillNames = allowedSkillNames,
                    instructions = instructions
                )
                // Resolve tools + MCP using SessionToolResolver (same as primary agent)
                val resolvedTools = sessionToolResolver.resolveToolsForAgent(agentDef, baseContext)
                return baseContext to resolvedTools
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean(SessionManager::class)
    fun sessionManager(
        sessionStore: AsyncSessionStore,
        agentStore: AsyncAgentStore,
        agentService: AgentService,
        configStore: ModelProviderConfigStore,
        sessionToolResolver: SessionToolResolver,
        @Autowired(required = false) skillRegistry: com.easy.easyai.skills.SkillRegistry? = null,
        @Autowired(required = false) todoStore: AsyncTodoStore? = null,
    ): SessionManager {
        // Agent lookup function
        val agentLookup: suspend (String, String) -> AgentDefinition? = { id, userId -> agentStore.findById(id, userId) }

        // Build skills data for prompt rendering (list of {name, description} maps)
        val skillsData = buildSkillsData(skillRegistry)

        // Create SessionAgentFactory — all tools created via ToolBuilder beans
        val sessionAgentFactory = SessionAgentFactory(
            agentBuilder = AgentBuilder.Default,
            agentService = agentService,
            defaultSystemPrompt = easyAiProperties.systemPrompt,
            messageListenerFactory = { sid, ctx ->
                (sessionStore as? R2dbcAsyncSessionStore)?.createMessageListener(sid, ctx)
            },
            messageTimestampsProvider = { sid ->
                sessionStore.loadMessagesWithTimestamps(sid)
                    .associate { it.message.id to it.timestamp }
            }
        )

        return DatabaseSessionManager(
            sessionStore = sessionStore,
            agentFactory = sessionAgentFactory,
            toolResolver = sessionToolResolver,
            configStore = configStore,
            agentLookup = agentLookup,
            todoStore = todoStore,
            skills = skillsData,
            agentStore = agentStore
        )
    }

    @Bean
    @ConditionalOnMissingBean(ModelProviderConfigStore::class)
    fun modelProviderConfigStore(initializer: R2dbcDatabaseInitializer): ModelProviderConfigStore {
        return R2dbcModelConfigStore(
            db = initializer.getDatabase()
        )
    }

    @Bean
    @ConditionalOnMissingBean(ModelConfigGroupStore::class)
    fun modelConfigGroupStore(initializer: R2dbcDatabaseInitializer): ModelConfigGroupStore {
        return R2dbcModelConfigGroupStore(
            db = initializer.getDatabase()
        )
    }

    @Bean
    @ConditionalOnMissingBean(AsyncAgentStore::class)
    fun asyncAgentStore(initializer: R2dbcDatabaseInitializer): AsyncAgentStore {
        return R2dbcAgentStore(
            db = initializer.getDatabase()
        )
    }

    @Bean
    @ConditionalOnMissingBean(AsyncProjectStore::class)
    fun asyncProjectStore(initializer: R2dbcDatabaseInitializer): AsyncProjectStore {
        return R2dbcAsyncProjectStore(
            db = initializer.getDatabase()
        )
    }

    @Bean
    @ConditionalOnMissingBean(AsyncTodoStore::class)
    fun asyncTodoStore(initializer: R2dbcDatabaseInitializer): AsyncTodoStore {
        return R2dbcAsyncTodoStore(
            db = initializer.getDatabase()
        )
    }

    @Bean
    @ConditionalOnMissingBean(PermissionRuleStore::class)
    fun permissionRuleStore(initializer: R2dbcDatabaseInitializer): PermissionRuleStore {
        return R2dbcAsyncPermissionRuleStore(
            db = initializer.getDatabase()
        )
    }

    @Bean
    @ConditionalOnMissingBean(PermissionService::class)
    fun permissionService(ruleStore: PermissionRuleStore, @Lazy toolFactory: ToolFactory): PermissionService {
        return PermissionService(
            ruleStore = ruleStore,
            toolFactory = toolFactory
        )
    }

    @Bean
    @ConditionalOnMissingBean(ModelConfigService::class)
    fun modelConfigService(configStore: ModelProviderConfigStore, groupStore: ModelConfigGroupStore): ModelConfigService {
        return DefaultModelConfigService(configStore, groupStore)
    }

    @Bean
    @ConditionalOnMissingBean(SubAgentMessageListenerFactory::class)
    fun subAgentMessageListenerFactory(
        sessionStore: AsyncSessionStore
    ): SubAgentMessageListenerFactory {
        return SubAgentMessageListenerFactory { sessionId, context, parentMessageId, parentToolCallId ->
            (sessionStore as? R2dbcAsyncSessionStore)?.createMessageListener(sessionId, context, parentMessageId, parentToolCallId)
        }
    }

    @Bean
    @ConditionalOnMissingBean(TodoCompletionCheck::class)
    @Order(20)
    fun todoCompletionCheck(todoStore: AsyncTodoStore): AgentCompletionCheck {
        return TodoCompletionCheck(todoStore)
    }

    // ─── Goal Beans ────────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(GoalStore::class)
    fun goalStore(initializer: R2dbcDatabaseInitializer): GoalStore {
        return SqlGoalStore(initializer.getDatabase())
    }

    @Bean
    @ConditionalOnMissingBean(GoalStatusNotifier::class)
    fun goalStatusNotifier(): GoalStatusNotifier {
        return GoalStatusNotifier()
    }

    @Bean
    @ConditionalOnMissingBean(GoalCompletionCheck::class)
    @Order(10)
    fun goalCompletionCheck(goalStore: GoalStore, goalStatusNotifier: GoalStatusNotifier): AgentCompletionCheck {
        return GoalCompletionCheck(goalStore, goalStatusNotifier)
    }

    // ─── MCP Beans ───────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(AsyncMcpServerStore::class)
    fun asyncMcpServerStore(initializer: R2dbcDatabaseInitializer): AsyncMcpServerStore {
        return R2dbcMcpServerStore(initializer.getDatabase())
    }

    @Bean
    @ConditionalOnMissingBean(McpClientManager::class)
    fun mcpClientManager(
        mcpServerStore: AsyncMcpServerStore,
    ): McpClientManager {
        return McpClientManager(mcpServerStore)
    }

    @Bean
    @ConditionalOnMissingBean(McpToolProvider::class)
    fun mcpToolProvider(mcpClientManager: McpClientManager): McpToolProvider {
        return McpToolProvider(mcpClientManager)
    }

    // ─── Auth Beans ──────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(UserStore::class)
    fun userStore(initializer: R2dbcDatabaseInitializer): UserStore {
        return R2dbcUserStore(initializer.getDatabase())
    }

    @Bean
    @ConditionalOnMissingBean(RefreshTokenStore::class)
    fun refreshTokenStore(initializer: R2dbcDatabaseInitializer): RefreshTokenStore {
        return R2dbcRefreshTokenStore(initializer.getDatabase())
    }

    // ─── Command Beans ──────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(AsyncUserCommandStore::class)
    fun asyncUserCommandStore(initializer: R2dbcDatabaseInitializer): AsyncUserCommandStore {
        return R2dbcAsyncUserCommandStore(initializer.getDatabase())
    }

    // ─── Swarm Beans ─────────────────────────────────────────────────────────────

    @Bean
    @ConditionalOnMissingBean(SwarmRunStore::class)
    @ConditionalOnProperty(prefix = "easyai.swarm", name = ["enabled"], havingValue = "true")
    fun swarmRunStore(initializer: R2dbcDatabaseInitializer): SwarmRunStore {
        return R2dbcSwarmRunStore(initializer.getDatabase())
    }

    @Bean
    @ConditionalOnMissingBean(SwarmPresetStore::class)
    @ConditionalOnProperty(prefix = "easyai.swarm", name = ["enabled"], havingValue = "true")
    fun swarmPresetStore(initializer: R2dbcDatabaseInitializer): SwarmPresetStore {
        return R2dbcSwarmPresetStore(initializer.getDatabase())
    }
}
