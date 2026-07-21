package com.easy.easyai.repository.session

import com.easy.easyai.compaction.OriginalMessageLoader
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.UserMessage
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.ObjectMapper
import com.easy.easyai.common.util.SharedObjectMapper
import org.slf4j.LoggerFactory

/**
 * R2DBC-based implementation of OriginalMessageLoader.
 * Loads original messages that were compacted in previous rounds by:
 * 1. Loading summary messages by their IDs
 * 2. Parsing compactedMessageIds from their metadata
 * 3. Loading the original messages by those IDs (including compacted ones)
 */
class R2dbcOriginalMessageLoader(
    private val store: R2dbcAsyncSessionStore,
    private val objectMapper: ObjectMapper = SharedObjectMapper.instance
) : OriginalMessageLoader {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun loadOriginalMessages(summaryMessageIds: List<String>): List<EasyAiMessage> {
        if (summaryMessageIds.isEmpty()) return emptyList()

        // 1. Load summary messages to extract compactedMessageIds
        val summaryMessages = store.loadMessagesByIds(summaryMessageIds)

        // 2. Parse compactedMessageIds from each summary's metadata
        val allOriginalIds = summaryMessages.flatMap { msg ->
            val idsJson = (msg as? UserMessage)?.metadata?.get("compactedMessageIds") ?: "[]"
            parseJsonArray(idsJson)
        }.distinct()

        if (allOriginalIds.isEmpty()) {
            logger.warn("No compactedMessageIds found in {} summary messages", summaryMessageIds.size)
            return emptyList()
        }

        // 3. Load original messages by IDs (includes compacted messages)
        val originalMessages = store.loadMessagesByIds(allOriginalIds)

        logger.info(
            "Loaded {} original messages from {} summary messages ({} unique IDs)",
            originalMessages.size, summaryMessageIds.size, allOriginalIds.size
        )

        return originalMessages
    }

    private fun parseJsonArray(json: String): List<String> {
        return try {
            objectMapper.readValue(json, object : TypeReference<List<String>>() {})
        } catch (e: Exception) {
            logger.warn("Failed to parse compactedMessageIds JSON: {}", json, e)
            emptyList()
        }
    }
}
