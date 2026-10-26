package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.api.config.ModelConfigService
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.SessionManager
import com.easy.easyai.core.task.AsyncTask
import com.easy.easyai.core.task.AsyncTaskRunner
import com.easy.easyai.core.task.AsyncTaskStore
import com.easy.easyai.core.task.ScheduledTaskSettingStore
import com.easy.easyai.core.task.TaskHandler
import com.easy.easyai.core.tool.ToolCapability
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.watch.Watch
import com.easy.easyai.core.watch.WatchNotifier
import com.easy.easyai.core.watch.WatchProperties
import com.easy.easyai.core.watch.WatchRunGateway
import com.easy.easyai.core.watch.WatchService
import com.easy.easyai.core.watch.WatchStore
import com.easy.easyai.core.watch.WatchTaskHandler
import com.easy.easyai.core.watch.WatchToolsetResolver
import com.easy.easyai.repository.session.SessionToolResolver
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import java.nio.file.Path

/**
 * Host wiring for the watch subsystem: the definition service, the planner that claims due rows,
 * the handler that runs one firing, and the adapters that connect them to the agent runtime.
 *
 * Both adapters re-derive their answer at run time rather than trusting creation-time data: the
 * toolset resolver re-reads the creating agent's bindings (a watch must not keep a tool the user
 * removed), and the gateway goes through [AsyncTaskRunner] so a page "cancel" interrupts a live
 * worker instead of only flipping the row's status.
 */
@AutoConfiguration(after = [R2dbcRepositoryAutoConfiguration::class])
@ConditionalOnProperty(prefix = "easyai.r2dbc", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@ConditionalOnProperty(prefix = "easyai.watch", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(WatchPropertiesBinding::class)
class WatchTaskAutoConfiguration {

    private val logger = LoggerFactory.getLogger(javaClass)

    @Bean
    @ConditionalOnMissingBean
    fun watchProperties(binding: WatchPropertiesBinding): WatchProperties = binding.toProperties()

    @Bean
    @ConditionalOnMissingBean
    fun watchService(
        store: WatchStore,
        properties: WatchProperties,
        settingStore: ObjectProvider<ScheduledTaskSettingStore>,
        taskStore: ObjectProvider<AsyncTaskStore>,
        runner: ObjectProvider<AsyncTaskRunner>
    ): WatchService = WatchService(
        store = store,
        settingStore = settingStore.ifAvailable,
        taskStore = taskStore.ifAvailable,
        runGateway = runner.ifAvailable?.let { asyncRunner -> AsyncTaskRunnerGateway(asyncRunner) },
        properties = properties
    )

    @Bean
    @ConditionalOnMissingBean(WatchTaskPlanner::class)
    fun watchTaskPlanner(store: WatchStore, properties: WatchProperties): WatchTaskPlanner =
        WatchTaskPlanner(store = store, properties = properties)

    @Bean
    @ConditionalOnMissingBean
    fun watchTaskHandler(
        store: WatchStore,
        properties: WatchProperties,
        sessionManager: ObjectProvider<SessionManager>,
        agentService: ObjectProvider<AgentService>,
        modelConfigService: ObjectProvider<ModelConfigService>,
        toolsetResolver: ObjectProvider<WatchToolsetResolver>,
        notifier: ObjectProvider<WatchNotifier>,
        taskStore: ObjectProvider<AsyncTaskStore>
    ): WatchTaskHandler = WatchTaskHandler(
        store = store,
        properties = properties,
        sessionManager = sessionManager.ifAvailable,
        agentService = agentService.ifAvailable,
        modelConfigService = modelConfigService.ifAvailable,
        toolsetResolver = toolsetResolver.ifAvailable,
        notifier = notifier.ifAvailable,
        taskStore = taskStore.ifAvailable
    )

    @Bean
    @ConditionalOnMissingBean
    fun watchToolsetResolver(
        toolResolver: SessionToolResolver,
        agentStore: ObjectProvider<AsyncAgentStore>
    ): WatchToolsetResolver = AgentWatchToolsetResolver(toolResolver, agentStore.ifAvailable)
}

/**
 * Resolves what a watch run may use: the creating agent's *current* tools, narrowed to the names
 * approved when the watch was created, and re-checked against the unattended safety rules.
 *
 * Creation already refused shell/interactive/file-writing tools; this second pass exists because
 * tool metadata and agent bindings are mutable — a tool that gained `tracksFileChanges` after the
 * watch was created must drop out, and an allowlisted name that the agent no longer binds must not
 * be resurrected.
 */
internal class AgentWatchToolsetResolver(
    private val toolResolver: SessionToolResolver,
    private val agentStore: AsyncAgentStore? = null
) : WatchToolsetResolver {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun resolve(watch: Watch, runSessionId: String): List<ToolDefinition> {
        val agentStore = agentStore ?: return emptyList()
        val owners = watch.owners.ifEmpty { setOf(watch.userId) }
        val agentDef = agentStore.findById(watch.sourceAgentId, owners)
        if (agentDef == null) {
            logger.warn(
                "Watch {} was created by agent '{}' which no longer exists; running without tools",
                watch.id, watch.sourceAgentId
            )
            return emptyList()
        }
        val context = AgentContext(
            agentId = watch.sourceAgentId,
            sessionId = runSessionId,
            userId = watch.userId,
            owners = owners,
            projectId = watch.projectId,
            projectPath = watch.projectPath?.let { Path.of(it) },
            projectKind = watch.projectKind,
            unattended = true
        )
        val approved = watch.allowedTools.toSet()
        val tools = toolResolver.resolveToolsForAgent(agentDef, context)
            .filter { tool -> tool.name in approved && tool.isSafeForUnattended() }
            .distinctBy { it.name }
        val missing = approved - tools.map { it.name }.toSet()
        if (missing.isNotEmpty()) {
            logger.info(
                "Watch {}: {} approved tool(s) are no longer available: {}",
                watch.id, missing.size, missing.sorted()
            )
        }
        return tools
    }

    /** A static tool must still declare READ_ONLY; an MCP tool was explicitly approved by name. */
    private fun ToolDefinition.isSafeForUnattended(): Boolean {
        if (tracksFileChanges) return false
        val blocked = setOf(
            ToolCapability.USER_INTERACTIVE,
            ToolCapability.SPAWNS_SUBAGENTS,
            ToolCapability.TEAM_COORDINATION,
            ToolCapability.SHELL_EXECUTION
        )
        if (capabilities.any { it in blocked }) return false
        return if (name.contains(MCP_SEPARATOR)) true else ToolCapability.READ_ONLY in capabilities
    }

    private companion object {
        const val MCP_SEPARATOR = "__"
    }
}

/** [WatchRunGateway] over the shared runner. */
internal class AsyncTaskRunnerGateway(
    private val runner: AsyncTaskRunner
) : WatchRunGateway {

    override suspend fun launch(task: AsyncTask): AsyncTask? = runner.submit(task)

    override suspend fun cancel(taskId: String): Boolean = runner.cancel(taskId)
}
