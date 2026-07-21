package com.easy.easyai.autoconfigure.r2dbc

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.TargetType
import com.easy.easyai.core.tool.ToolFactory
import com.easy.easyai.repository.agent.AgentSeedData
import com.easy.easyai.skills.command.BuiltinCommandHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.time.Duration.Companion.milliseconds

/**
 * Seeds default agent definitions into the database at startup.
 * Runs after [R2dbcDatabaseInitializer] completes migration.
 * Idempotent: skips if seed records already exist.
 *
 * Merges two responsibilities:
 * 1. Creates the default coding agent (with tools resolved from [ToolFactory]) if no agents exist.
 * 2. Seeds sub-agent definitions from [AgentSeedData].
 */
class AgentSeedInitializer(
    private val agentStore: AsyncAgentStore,
    private val dbInitializer: R2dbcDatabaseInitializer,
    private val toolFactory: ToolFactory,
    private val builtinCommandHandlers: List<BuiltinCommandHandler> = emptyList()
) : SmartLifecycle {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val running = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        const val DEFAULT_AGENT_ID = "default-agent"
        const val ASK_AGENT_ID = "ask-agent"
    }

    override fun start() {
        if (running.compareAndSet(false, true)) {
            scope.launch {
                // Wait for database migration to complete (with timeout)
                val timeoutMs = 30_000L
                val startTime = System.currentTimeMillis()
                while (!dbInitializer.isInitialized()) {
                    if (System.currentTimeMillis() - startTime > timeoutMs) {
                        logger.error("Timed out waiting for R2DBC database initialization after {}ms, skipping seed", timeoutMs)
                        return@launch
                    }
                    kotlinx.coroutines.delay(100.milliseconds)
                }
                try {
                    seedDefaultCodingAgent()
                    seedAskAgent()
                    seedSubAgents()
                } catch (e: Exception) {
                    logger.error("Failed to seed default agents", e)
                }
            }
        }
    }

    /**
     * Creates the default coding agent if no agents exist.
     * Resolves default tool names from [ToolFactory] metadata.
     */
    private suspend fun seedDefaultCodingAgent() {
        val existingCount = agentStore.count()
        if (existingCount > 0) {
            logger.debug("Agents already exist (count={}), skipping default coding agent", existingCount)
            return
        }

        logger.info("Initializing default coding agent...")

        val defaultToolNames = toolFactory.getBuilders()
            .filter { it.isDefaultTool }
            .map { it.name }

        val defaultAgent = AgentDefinition.create(
            id = DEFAULT_AGENT_ID,
            name = "Code",
            description = "Default coding agent: plans and executes code changes",
            customInstructions = "You are a coding assistant. Analyze requirements, plan your approach, and implement solutions using the available tools. Follow best practices and ensure code quality.",
            toolNames = defaultToolNames
        )
        agentStore.save(defaultAgent, AuthConstants.SYSTEM_USER_ID)
        agentStore.saveAgentTools(DEFAULT_AGENT_ID, defaultToolNames)

        val builtinCommandNames = builtinCommandHandlers.map { it.name }
        if (builtinCommandNames.isNotEmpty()) {
            agentStore.saveAgentCommands(DEFAULT_AGENT_ID, builtinCommandNames)
        }
        logger.info("Created default agent: {} with tools: {} and commands: {}", DEFAULT_AGENT_ID, defaultToolNames, builtinCommandNames)
    }

    /**
     * Creates the Ask agent if it doesn't exist.
     * The Ask agent only has read-only tools (tools that do not modify files or system state).
     */
    private suspend fun seedAskAgent() {
        val existing = agentStore.findById(ASK_AGENT_ID, AuthConstants.SYSTEM_USER_ID)
        if (existing != null) {
            logger.debug("Ask agent already exists, skipping")
            return
        }

        logger.info("Initializing Ask agent...")

        val readOnlyToolNames = toolFactory.getBuilders()
            .filter { !it.tracksFileChanges && it.isDefaultTool }
            .map { it.name }

        val askAgent = AgentDefinition.create(
            id = ASK_AGENT_ID,
            name = "Ask",
            description = "Read-only agent: answers questions by reading and searching code, files, memory, and the web. Cannot modify files or execute commands.",
            customInstructions = "You are a read-only assistant. Your job is to answer questions by reading files, searching code, and looking up information. You cannot write files, execute commands, or make any changes. Be thorough in your research and provide clear, well-sourced answers.",
            toolNames = readOnlyToolNames
        )
        agentStore.save(askAgent, AuthConstants.SYSTEM_USER_ID)
        agentStore.saveAgentTools(ASK_AGENT_ID, readOnlyToolNames)
        logger.info("Created Ask agent: {} with tools: {}", ASK_AGENT_ID, readOnlyToolNames)
    }

    /**
     * Seeds sub-agent definitions and tool configs from [AgentSeedData].
     */
    private suspend fun seedSubAgents() {
        var inserted = 0
        for (agent in AgentSeedData.agents) {
            val existing = agentStore.findById(agent.id, AuthConstants.SYSTEM_USER_ID)
            if (existing == null) {
                agentStore.save(agent, AuthConstants.SYSTEM_USER_ID)
                inserted++
            }
        }

        // Seed tool configs
        var toolConfigsInserted = 0
        for ((agentId, toolNames) in AgentSeedData.toolConfigs) {
            val existing = agentStore.getAgentToolConfigs(agentId, TargetType.TOOL)
            if (existing.isEmpty()) {
                agentStore.saveAgentToolConfigs(agentId, TargetType.TOOL, toolNames)
                toolConfigsInserted += toolNames.size
            }
        }

        if (inserted > 0 || toolConfigsInserted > 0) {
            logger.info("Seeded {} sub-agents and {} tool configs", inserted, toolConfigsInserted)
        } else {
            logger.debug("Sub-agents already seeded, skipping")
        }
    }

    override fun stop() {
        running.set(false)
    }

    override fun isRunning(): Boolean = running.get()

    override fun isAutoStartup(): Boolean = true

    override fun getPhase(): Int = Int.MIN_VALUE + 10 // After R2dbcDatabaseInitializer (MIN_VALUE)
}
