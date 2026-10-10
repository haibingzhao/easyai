package com.easy.easyai.compaction

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.strategy.CompactionStrategy
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.CompactionTriggerType
import com.easy.easyai.core.agent.TransformContextInput
import com.easy.easyai.core.agent.TransformContextService
import com.easy.easyai.core.memory.MemoryFlushAgent
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import org.slf4j.LoggerFactory
import com.easy.easyai.api.llm.ChatModel

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
    private val memoryFlushAgent: MemoryFlushAgent? = null,
    private val auxModelResolver: AuxModelResolver? = null
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
     * The model compaction actually runs on: a per-user configured COMPACTION model overrides the
     * session model, replacing both the [ChatModel] and the [AgentContext.modelConfig] (so option
     * building — model name, protocol, thinking toggle — matches the configured provider).
     *
     * An absent resolver, an unconfigured task, or an unresolvable config all fall back to the
     * session model. The trigger/range logic still uses the session model's context window, since
     * it is the session conversation being compacted; the configured model should have a window at
     * least as large as one summarization range.
     */
    private suspend fun effectiveCompactionModel(
        agentContext: AgentContext,
        sessionChatModel: ChatModel?
    ): Pair<AgentContext, ChatModel?> {
        val resolved = auxModelResolver?.resolve(agentContext.effectiveOwners, AuxModelTask.COMPACTION)
            ?: return agentContext to sessionChatModel
        return agentContext.copy(modelConfig = resolved.modelConfig) to resolved.chatModel
    }

    override suspend fun transform(input: TransformContextInput): List<EasyAiMessage> {
        if (!config.enabled) {
            return input.messages
        }

        val triggerChecker = CompactionTriggerChecker(config, tokenEstimator)
        // Folded views measure smaller: trigger on the view actually sent to the LLM,
        // while selection and summarization below still operate on the original messages.
        val measureMessages = input.compactionMeasureMessages ?: input.messages
        val shouldCompact = triggerChecker.shouldCompact(
            measureMessages,
            input.modelContextLength,
            input.compactionTriggerType
        )

        if (shouldCompact) {
            // Resolve the compaction model (per-user override, else the session model)
            val (compactionContext, chatModel) = effectiveCompactionModel(input.agentContext, input.chatModel)

            // Flush memory before compaction to prevent losing important facts
            if (memoryFlushAgent != null && chatModel != null) {
                try {
                    memoryFlushAgent.maybeFlush(
                        agentContext = compactionContext,
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
                agentContext = compactionContext,
                messages = input.messages,
                turnId = input.turnId,
                modelContextLength = input.modelContextLength,
                eventScope = eventPusher,
                messageTimestamps = input.messageTimestamps,
                chatModel = chatModel
            )
        }

        return input.messages
    }

    /**
     * Trigger manual compaction with a real-time event pusher.
     * Events are pushed to the provided [eventPusher] in real-time during compaction.
     * Returns the compacted messages.
     * If compaction is not needed or fails, returns original messages.
     *
     * @param chatModel Optional session-specific ChatModel for LLM-based strategies.
     */
    suspend fun manualCompactWithPusher(
        agentContext: AgentContext,
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

        // Resolve the compaction model (per-user override, else the session model)
        val (compactionContext, effectiveChatModel) = effectiveCompactionModel(agentContext, chatModel)

        // Flush memory before manual compaction (context is likely near window limit)
        if (memoryFlushAgent != null && effectiveChatModel != null) {
            try {
                memoryFlushAgent.maybeFlush(
                    agentContext = compactionContext,
                    messages = messages,
                    modelContextLength = modelContextLength,
                    estimatedTokenCount = tokenEstimator.estimate(messages),
                    chatModel = effectiveChatModel
                )
            } catch (e: Exception) {
                logger.warn("Memory flush failed before manual compaction: {}", e.message)
            }
        }

        return orchestrator.compact(
            agentContext = compactionContext,
            messages = messages,
            turnId = turnId,
            modelContextLength = modelContextLength,
            triggerType = CompactionTriggerType.Manual,
            eventScope = eventPusher,
            messageTimestamps = messageTimestamps,
            chatModel = effectiveChatModel
        )
    }
}
