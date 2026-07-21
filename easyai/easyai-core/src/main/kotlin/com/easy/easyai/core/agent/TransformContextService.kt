package com.easy.easyai.core.agent

import com.easy.easyai.core.model.EasyAiMessage

/**
 * Service for transforming the agent context before each LLM call.
 *
 * Implementations can perform operations like context compaction,
 * message filtering, or other transformations on the message history.
 */
interface TransformContextService {
    /**
     * Transform the context before sending to the LLM.
     *
     * @param input The transformation input containing messages and metadata
     * @return The transformed list of messages
     */
    suspend fun transform(input: TransformContextInput): List<EasyAiMessage>
}