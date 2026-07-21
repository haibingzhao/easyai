package com.easy.easyai.core.agent

import com.easy.easyai.core.event.AgentEvent
import com.easy.easyai.core.model.EasyAiMessage
import org.springframework.ai.chat.model.ChatModel

/**
 * Input for the transformContext extension point.
 * Provides all context needed for transformations like context compaction.
 *
 * @property compactionTriggerType The trigger type for compaction.
 *   When [CompactionTriggerType.Manual] or [CompactionTriggerType.Overflow],
 *   the compaction check interval is bypassed and compaction is evaluated immediately.
 * @property messageTimestamps Map of messageId to createdAt timestamp (epoch millis).
 *   Used by compaction to determine correct ordering of the summary message.
 *   May be empty if timestamps are not available (e.g., in-memory sessions).
 * @property eventPusher Optional callback for emitting events (e.g., compaction start/end)
 *   to the agent's event stream during transformation. May be null if events are not needed.
 * @property chatModel The session-specific ChatModel instance. Used by compaction strategies
 *   that need LLM calls (e.g., LlmSummaryStrategy). May be null if not available.
 */
data class TransformContextInput(
    val agentContext: AgentContext,
    val messages: List<EasyAiMessage>,
    val turnId: Int,
    val modelContextLength: Int,
    val compactionTriggerType: CompactionTriggerType = CompactionTriggerType.Auto,
    val messageTimestamps: Map<String, Long> = emptyMap(),
    val eventPusher: (suspend (AgentEvent) -> Unit)? = null,
    val chatModel: ChatModel? = null
)
