package com.easy.easyai.autoconfigure.swarm

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.common.textio.template.TemplateRenderer
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.SubAgentContextResolver
import com.easy.easyai.swarm.event.SwarmEventBridge
import com.easy.easyai.swarm.preset.SwarmPresetStore
import com.easy.easyai.swarm.runtime.SwarmAgentResolver
import com.easy.easyai.swarm.runtime.SwarmRuntime
import com.easy.easyai.swarm.runtime.SwarmWorkerExecutor
import com.easy.easyai.swarm.store.SwarmRunStore
import com.easy.easyai.swarm.tool.SwarmToolBuilder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Lazy
import java.time.Instant

/**
 * Auto-configuration for swarm orchestration.
 *
 * Enabled when `easyai.swarm.enabled=true`.
 * Provides SwarmRuntime, SwarmEventBridge, and SwarmToolBuilder beans.
 *
 * Session persistence is handled by [SwarmSessionAutoConfiguration] (activated separately
 * when easyai-repository is on the classpath).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(SwarmRuntime::class)
@ConditionalOnProperty(prefix = "easyai.swarm", name = ["enabled"], havingValue = "true")
@EnableConfigurationProperties(SwarmProperties::class)
class SwarmAutoConfiguration(
    private val properties: SwarmProperties
) {

    @Bean
    @ConditionalOnMissingBean
    fun swarmEventBridge(): SwarmEventBridge {
        return SwarmEventBridge()
    }

    @Bean
    @ConditionalOnMissingBean
    fun swarmAgentResolver(
        agentStore: AsyncAgentStore,
        contextResolver: SubAgentContextResolver,
        templateRenderer: TemplateRenderer,
        @Autowired(required = false) modelConfigStore: ModelProviderConfigStore?,
    ): SwarmAgentResolver {
        return SwarmAgentResolver(
            agentStore = agentStore,
            contextResolver = contextResolver,
            templateRenderer = templateRenderer,
            modelConfigStore = modelConfigStore,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun swarmWorkerExecutor(
        @Lazy agentService: AgentService,
        agentResolver: SwarmAgentResolver,
        templateRenderer: TemplateRenderer,
        eventBridge: SwarmEventBridge,
    ): SwarmWorkerExecutor {
        return SwarmWorkerExecutor(
            agentServiceProvider = { agentService },
            agentResolver = agentResolver,
            templateRenderer = templateRenderer,
            eventBridge = eventBridge,
            eventVerbosity = properties.eventVerbosity,
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun swarmRuntime(
        workerExecutor: SwarmWorkerExecutor,
        agentResolver: SwarmAgentResolver,
        eventBridge: SwarmEventBridge,
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

    @Bean
    @ConditionalOnMissingBean
    fun serverStartupTime(): Instant = Instant.now()

    @Bean
    @ConditionalOnMissingBean
    fun swarmToolBuilder(
        runtime: SwarmRuntime,
        presetStore: SwarmPresetStore,
        @Autowired(required = false) swarmRunStore: SwarmRunStore?
    ): SwarmToolBuilder {
        return SwarmToolBuilder(runtime, presetStore, swarmRunStore)
    }
}
