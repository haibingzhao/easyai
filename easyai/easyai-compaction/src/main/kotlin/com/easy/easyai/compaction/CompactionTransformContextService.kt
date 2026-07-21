package com.easy.easyai.compaction

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.core.agent.CompactionTriggerType
import com.easy.easyai.core.agent.TransformContextInput
import com.easy.easyai.core.agent.TransformContextService
import com.easy.easyai.core.memory.MemoryFlushAgent
import com.easy.easyai.core.model.EasyAiMessage
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.model.ChatModel

/**
 * TransformContextService implementation that applies context compaction when needed.
 *
 * This service monitors the conversation context and triggers compaction
 * (summarization) when the context exceeds configured thresholds.
 */
class CompactionTransformContextService(
    private val config: CompactionConfig,
    private val strategy: CompactionStrategy,
    private val tokenEstimator: TokenEstimator,
    listener: CompactionListener? = null,
    originalMessageLoader: OriginalMessageLoader? = null,
    private val memoryFlushAgent: MemoryFlushAgent? = null
) : TransformContextService {

    private val logger = LoggerFactory.getLogger(javaClass)

    private val orchestrator = ContextCompactionOrchestrator(
        config = config,
        strategy = strategy,
        tokenEstimator = tokenEstimator,
        compactionListener = listener,
        originalMessageLoader = originalMessageLoader
    )

    /**
     * Message count at which compaction was last checked.
     * Uses message count instead of turnId because turnId resets to 0
     * when resuming from a historical session, which would prevent
     * compaction from ever being triggered for sessions with many messages.
     */
    private var lastCheckMessageCount = 0

    override suspend fun transform(input: TransformContextInput): List<EasyAiMessage> {
        if (!config.enabled) {
            return input.messages
        }

        // For Manual and Overflow triggers, bypass the check interval and evaluate immediately.
        // For Auto triggers, use message count delta to throttle expensive token estimation checks.
        // This ensures compaction works correctly when resuming from historical sessions
        // where turnId resets to 0 but messages may already exceed the threshold.
        val isUrgentTrigger = input.compactionTriggerType is CompactionTriggerType.Manual ||
            input.compactionTriggerType is CompactionTriggerType.Overflow
        val messageCountDelta = input.messages.size - lastCheckMessageCount
        val shouldCheck = isUrgentTrigger || messageCountDelta >= config.checkInterval

        if (shouldCheck) {
            lastCheckMessageCount = input.messages.size

            val triggerChecker = CompactionTriggerChecker(config, tokenEstimator)
            val shouldCompact = triggerChecker.shouldCompact(
                input.messages,
                input.modelContextLength,
                input.compactionTriggerType
            )

            if (shouldCompact) {
                // Flush memory before compaction to prevent losing important facts
                val chatModel = input.chatModel
                if (memoryFlushAgent != null && chatModel != null) {
                    try {
                        memoryFlushAgent.maybeFlush(
                            agentContext = input.agentContext,
                            messages = input.messages,
                            modelContextLength = input.modelContextLength,
                            estimatedTokenCount = tokenEstimator.estimate(input.messages),
                            chatModel = chatModel
                        )
                    } catch (e: Exception) {
                        // Flush failure should not prevent compaction
                        logger.warn("Memory flush failed, proceeding with compaction: {}", e.message)
                    }
                }

                val eventPusher = input.eventPusher?.let { pusher ->
                    ContextCompactionOrchestrator.EventPusher { event -> pusher(event) }
                }
                return orchestrator.compact(
                    agentContext = input.agentContext,
                    messages = input.messages,
                    turnId = input.turnId,
                    modelContextLength = input.modelContextLength,
                    eventScope = eventPusher,
                    messageTimestamps = input.messageTimestamps,
                    chatModel = input.chatModel
                )
            }
        }

        return input.messages
    }

    /**
     * Trigger manual compaction with a real-time event pusher.
     * Bypasses the check interval and always evaluates compaction.
     * Events are pushed to the provided [eventPusher] in real-time during compaction.
     * Returns the compacted messages.
     * If compaction is not needed or fails, returns original messages.
     *
     * @param chatModel Optional session-specific ChatModel for LLM-based strategies.
     */
    suspend fun manualCompactWithPusher(
        agentContext: com.easy.easyai.core.agent.AgentContext,
        messages: List<EasyAiMessage>,
        turnId: Int,
        modelContextLength: Int,
        eventPusher: ContextCompactionOrchestrator.EventPusher,
        messageTimestamps: Map<String, Long> = emptyMap(),
        chatModel: ChatModel? = null
    ): List<EasyAiMessage> {
        if (!config.enabled) {
            return messages
        }

        // Flush memory before manual compaction (context is likely near window limit)
        if (memoryFlushAgent != null && chatModel != null) {
            try {
                memoryFlushAgent.maybeFlush(
                    agentContext = agentContext,
                    messages = messages,
                    modelContextLength = modelContextLength,
                    estimatedTokenCount = tokenEstimator.estimate(messages),
                    chatModel = chatModel
                )
            } catch (e: Exception) {
                logger.warn("Memory flush failed before manual compaction: {}", e.message)
            }
        }

        return orchestrator.compact(
            agentContext = agentContext,
            messages = messages,
            turnId = turnId,
            modelContextLength = modelContextLength,
            triggerType = CompactionTriggerType.Manual,
            eventScope = eventPusher,
            messageTimestamps = messageTimestamps,
            chatModel = chatModel
        )
    }
}
