package com.easy.easyai.autoconfigure.swarm

import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.PersistedSession
import com.easy.easyai.core.event.MessageListener
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.repository.session.AsyncSessionStore
import com.easy.easyai.repository.session.R2dbcAsyncSessionStore
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.runtime.SwarmAgentResolver
import com.easy.easyai.swarm.runtime.SwarmRuntime
import com.easy.easyai.swarm.runtime.SwarmSessionManager
import com.easy.easyai.swarm.runtime.SwarmWorkerExecutor
import com.easy.easyai.swarm.store.SwarmRunStore
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.AutoConfigureAfter
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import org.springframework.context.annotation.Primary
import java.time.Instant
import java.util.UUID

/**
 * Auto-configuration for swarm session persistence.
 *
 * Activated only when both SwarmRuntime and AsyncSessionStore are on the classpath.
 * Replaces the base SwarmRuntime bean with one that creates sessions and persists messages
 * for each swarm worker execution.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(name = [
    "com.easy.easyai.swarm.runtime.SwarmRuntime",
    "com.easy.easyai.repository.session.AsyncSessionStore"
])
@ConditionalOnProperty(prefix = "easyai.swarm", name = ["enabled"], havingValue = "true")
@AutoConfigureAfter(SwarmAutoConfiguration::class)
class SwarmSessionAutoConfiguration {

    @Bean
    @Primary
    fun swarmWorkerExecutorWithSession(
        @Lazy agentService: AgentService,
        agentResolver: SwarmAgentResolver,
        templateRenderer: TemplateRenderer,
        eventBridge: SwarmEventBridge,
        properties: SwarmProperties,
        @Autowired(required = false) sessionStore: AsyncSessionStore?,
    ): SwarmWorkerExecutor {
        val sessionManager = sessionStore?.let { DefaultSwarmSessionManager(it) }

        return SwarmWorkerExecutor(
            agentServiceProvider = { agentService },
            agentResolver = agentResolver,
            templateRenderer = templateRenderer,
            eventBridge = eventBridge,
            sessionManager = sessionManager,
            eventVerbosity = properties.eventVerbosity,
        )
    }

    @Bean
    @Primary
    fun swarmRuntimeWithSession(
        workerExecutor: SwarmWorkerExecutor,
        agentResolver: SwarmAgentResolver,
        eventBridge: SwarmEventBridge,
        properties: SwarmProperties,
        @Autowired(required = false) store: SwarmRunStore?
    ): SwarmRuntime {
        return SwarmRuntime(
            workerExecutor = workerExecutor,
            agentResolver = agentResolver,
            eventBridge = eventBridge,
            maxConcurrency = properties.maxConcurrency,
            store = store,
        )
    }

    /**
     * Default [SwarmSessionManager] backed by an [AsyncSessionStore].
     *
     * Session creation works for any [AsyncSessionStore] implementation.
     * Message listener creation requires [R2dbcAsyncSessionStore]; other implementations
     * return null from [createMessageListener].
     */
    private class DefaultSwarmSessionManager(
        private val sessionStore: AsyncSessionStore
    ) : SwarmSessionManager {

        override suspend fun createSession(
            agentId: String,
            swarmRunId: String,
            swarmTaskId: String,
            userId: String
        ): String {
            val sessionId = UUID.randomUUID().toString()
            val now = Instant.now()
            val session = PersistedSession(
                id = sessionId,
                messages = emptyList(),
                createdAt = now,
                updatedAt = now,
                swarmRunId = swarmRunId,
                swarmTaskId = swarmTaskId
            )
            sessionStore.save(session, userId)
            return sessionId
        }

        override fun createMessageListener(sessionId: String, context: AgentContext): MessageListener? {
            return if (sessionStore is R2dbcAsyncSessionStore) {
                sessionStore.createMessageListener(sessionId, context)
            } else null
        }

        override suspend fun loadMessages(sessionId: String): List<EasyAiMessage> {
            return sessionStore.loadActiveMessages(sessionId)
        }

        override suspend fun findSessionByTask(swarmRunId: String, swarmTaskId: String): String? {
            return sessionStore.findSessionIdBySwarmTask(swarmRunId, swarmTaskId)
        }
    }
}
