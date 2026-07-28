package com.easy.easyai.autoconfigure.compaction

import com.easy.easyai.compaction.CompactionConfig
import com.easy.easyai.compaction.CompactionTransformContextService
import com.easy.easyai.compaction.CompactionListener
import com.easy.easyai.compaction.OriginalMessageLoader
import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.estimator.UsageAwareTokenEstimator
import com.easy.easyai.compaction.strategy.CompactionAgentStrategy
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.agent.TransformContextService
import com.easy.easyai.core.memory.MemoryFlushAgent
import com.easy.easyai.core.memory.MemoryStore
import org.springframework.ai.chat.model.ChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Auto-configuration for context compaction.
 *
 * Provides a `transformContext` bean that applies compaction when needed.
 * Enabled when `easyai.compaction.enabled=true` (default).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass(CompactionTransformContextService::class)
@ConditionalOnProperty(prefix = "easyai.compaction", name = ["enabled"], havingValue = "true", matchIfMissing = true)
@EnableConfigurationProperties(CompactionProperties::class)
class CompactionAutoConfiguration(
    private val properties: CompactionProperties
) {

    @Bean
    @ConditionalOnMissingBean
    fun tokenEstimator(): TokenEstimator = UsageAwareTokenEstimator()

    @Bean
    @ConditionalOnMissingBean
    fun compactionStrategy(
        agentServiceProvider: ObjectProvider<AgentService>,
        @Autowired(required = false) chatModel: ChatModel?
    ): CompactionStrategy {
        // Agent-based compaction: uses a lightweight Agent loop with update_variable tool.
        // ObjectProvider resolves AgentService lazily to avoid circular dependency:
        // AgentService -> TransformContextService -> CompactionStrategy -> AgentService
        return CompactionAgentStrategy(
            agentServiceProvider = { agentServiceProvider.getObject() },
            fallbackChatModel = chatModel
        )
    }

    @Bean
    @ConditionalOnMissingBean
    fun compactionConfig(): CompactionConfig = CompactionConfig(
        enabled = properties.enabled,
        threshold = properties.threshold,
        reservedTokens = properties.reservedTokens,
        tailTurns = properties.tailTurns,
        preserveRecentTokensRatio = properties.preserveRecentTokensRatio,
        minMessagesForCompaction = properties.minMessages
    )

    /**
     * The TransformContextService bean that will be injected into AgentService.
     * Applies context compaction when needed.
     */
    @Bean
    @ConditionalOnMissingBean(TransformContextService::class)
    fun transformContextService(
        config: CompactionConfig,
        strategy: CompactionStrategy,
        tokenEstimator: TokenEstimator,
        @Autowired(required = false) listener: CompactionListener?,
        @Autowired(required = false) originalMessageLoader: OriginalMessageLoader?,
        @Autowired(required = false) memoryStore: MemoryStore?
    ): TransformContextService {
        val memoryFlushAgent = memoryStore?.let {
            MemoryFlushAgent(
                store = it,
                threshold = properties.memoryFlushThreshold
            )
        }
        return CompactionTransformContextService(
            config = config,
            strategy = strategy,
            tokenEstimator = tokenEstimator,
            listener = listener,
            originalMessageLoader = originalMessageLoader,
            memoryFlushAgent = memoryFlushAgent
        )
    }
}
