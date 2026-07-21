package com.easy.easyai.autoconfigure.compaction

import com.easy.easyai.compaction.CompactionConfig
import com.easy.easyai.compaction.CompactionTransformContextService
import com.easy.easyai.compaction.CompactionListener
import com.easy.easyai.compaction.OriginalMessageLoader
import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.estimator.UsageAwareTokenEstimator
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.compaction.strategy.CompositeCompactionStrategy
import com.easy.easyai.compaction.strategy.LlmSummaryStrategy
import com.easy.easyai.compaction.strategy.SummaryStrategy
import com.easy.easyai.core.agent.TransformContextService
import com.easy.easyai.core.memory.MemoryFlushAgent
import com.easy.easyai.core.memory.MemoryStore
import org.springframework.ai.chat.model.ChatModel
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
        @Autowired(required = false) chatModel: ChatModel?
    ): CompactionStrategy {
        val summaryStrategy = SummaryStrategy()
        return when (properties.strategy) {
            "llm" -> {
                // Explicitly configured LLM strategy
                // Uses session-specific ChatModel at runtime, falls back to system-level if provided
                LlmSummaryStrategy(chatModel)
            }
            "summary" -> summaryStrategy
            else -> {
                // "auto" (default): layered progressive strategy
                // Round 1 uses SummaryStrategy (fast, free), Round 2+ uses LlmSummaryStrategy (high quality)
                // LlmSummaryStrategy uses session-specific ChatModel at runtime
                CompositeCompactionStrategy(summaryStrategy, LlmSummaryStrategy(chatModel))
            }
        }
    }

    @Bean
    @ConditionalOnMissingBean
    fun compactionConfig(): CompactionConfig = CompactionConfig(
        enabled = properties.enabled,
        threshold = properties.threshold,
        reservedTokens = properties.reservedTokens,
        tailTurns = properties.tailTurns,
        preserveRecentTokensRatio = properties.preserveRecentTokensRatio,
        minMessagesForCompaction = properties.minMessages,
        checkInterval = properties.checkInterval
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
