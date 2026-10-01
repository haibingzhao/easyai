package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.GenerationResult
import com.alibaba.dashscope.aigc.generation.GenerationUsage
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationUsage
import com.alibaba.dashscope.common.MultiModalMessage
import com.alibaba.dashscope.tools.ToolCallFunction
import org.slf4j.LoggerFactory

/** One tool-call delta as the provider reported it for a single stream frame. */
internal data class DashScopeToolCallFragment(
    val slot: Int,
    val id: String?,
    val name: String?,
    val arguments: String?
)

/** Token accounting of one frame; absent on every frame but the last for most models. */
internal data class DashScopeTokenUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val totalTokens: Int,
    val cacheReadTokens: Long?,
    val cacheWriteTokens: Long?,
    val reasoningTokens: Int?
)

/**
 * Protocol-normalized view of one DashScope stream frame (or of the single synchronous result).
 *
 * Both the text and the multimodal endpoint are reduced to this shape so [DashScopeStreamingAccumulator]
 * and [DashScopeChatModel] have exactly one code path, and so tests can feed frames without
 * constructing SDK value types.
 */
internal data class DashScopeChunk(
    val requestId: String?,
    val reasoningDelta: String? = null,
    val textDelta: String? = null,
    val toolCallFragments: List<DashScopeToolCallFragment> = emptyList(),
    val finishReason: String? = null,
    val usage: DashScopeTokenUsage? = null,
    /** Non-null when the frame carries an in-band error instead of content. */
    val error: DashScopeError? = null
)

internal data class DashScopeError(
    val statusCode: Int,
    val code: String?,
    val message: String?
)

/**
 * Maps SDK result objects into [DashScopeChunk].
 */
internal object DashScopeResponseMapper {

    private val logger = LoggerFactory.getLogger(DashScopeResponseMapper::class.java)

    fun fromGeneration(result: GenerationResult): DashScopeChunk {
        result.errorOrNull()?.let { return DashScopeChunk(requestId = result.requestId, error = it) }
        val output = result.output
        val choice = output?.choices?.firstOrNull()
        val message = choice?.message
        // Legacy single-turn shape: output.text with output.finish_reason and no choices.
        val text = message?.content ?: output?.text
        val fragments = message?.toolCalls.orEmpty().mapIndexedNotNull { index, call ->
            (call as? ToolCallFunction)?.toFragment(index)
        }
        return DashScopeChunk(
            requestId = result.requestId,
            reasoningDelta = message?.reasoningContent,
            textDelta = text?.takeIf { it.isNotEmpty() },
            toolCallFragments = fragments,
            finishReason = choice?.finishReason?.takeIf { it.isNotEmpty() }
                ?: output?.finishReason?.takeIf { it.isNotEmpty() },
            usage = result.usage?.toTokenUsage()
        )
    }

    fun fromMultiModal(result: MultiModalConversationResult): DashScopeChunk {
        result.errorOrNull()?.let { return DashScopeChunk(requestId = result.requestId, error = it) }
        val output = result.output
        val choice = output?.choices?.firstOrNull()
        val message = choice?.message
        val text = message?.multiModalText()
        val fragments = message?.toolCalls.orEmpty().mapIndexedNotNull { index, call ->
            (call as? ToolCallFunction)?.toFragment(index)
        }
        return DashScopeChunk(
            requestId = result.requestId,
            reasoningDelta = message?.reasoningContent,
            textDelta = text?.takeIf { it.isNotEmpty() },
            toolCallFragments = fragments,
            finishReason = choice?.finishReason?.takeIf { it.isNotEmpty() }
                ?: output?.finishReason?.takeIf { it.isNotEmpty() },
            usage = result.usage?.toTokenUsage()
        )
    }

    /** A frame is an error when the service reported a non-200 status or a populated error code. */
    private fun GenerationResult.errorOrNull(): DashScopeError? =
        statusCode.takeIf { it != null && it != 200 }?.let {
            DashScopeError(it, code.orNullWhenBlank(), message.orNullWhenBlank())
        }

    private fun MultiModalConversationResult.errorOrNull(): DashScopeError? =
        statusCode.takeIf { it != null && it != 200 }?.let {
            DashScopeError(it, code.orNullWhenBlank(), message.orNullWhenBlank())
        }

    /**
     * Multimodal content is a list of typed maps; only `text` entries are answer text, and the
     * model never returns image parts for a conversation turn.
     */
    private fun MultiModalMessage.multiModalText(): String? = content
        ?.mapNotNull { part -> part["text"] as? String }
        ?.joinToString(separator = "")
        ?.takeIf { it.isNotEmpty() }

    private fun ToolCallFunction.toFragment(index: Int): DashScopeToolCallFragment? {
        val function = this.function
        if (function == null && id.isNullOrEmpty()) {
            logger.debug("Ignoring a tool call frame without id or function payload")
            return null
        }
        return DashScopeToolCallFragment(
            slot = this.index ?: index,
            id = id,
            name = function?.name,
            arguments = function?.arguments
        )
    }

    private fun GenerationUsage.toTokenUsage(): DashScopeTokenUsage {
        val prompt = promptTokensDetails
        val output = outputTokensDetails
        return DashScopeTokenUsage(
            inputTokens = inputTokens ?: 0,
            outputTokens = outputTokens ?: 0,
            totalTokens = totalTokens ?: (inputTokens ?: 0) + (outputTokens ?: 0),
            cacheReadTokens = prompt?.cachedTokens?.toLong(),
            cacheWriteTokens = prompt?.cacheCreationInputTokens?.toLong(),
            reasoningTokens = output?.reasoningTokens
        )
    }

    private fun MultiModalConversationUsage.toTokenUsage(): DashScopeTokenUsage {
        val prompt = inputTokensDetails
        val output = outputTokensDetails
        return DashScopeTokenUsage(
            inputTokens = inputTokens ?: 0,
            outputTokens = outputTokens ?: 0,
            totalTokens = totalTokens ?: (inputTokens ?: 0) + (outputTokens ?: 0),
            cacheReadTokens = prompt?.cachedTokens?.toLong(),
            cacheWriteTokens = prompt?.cacheCreationInputTokens?.toLong(),
            reasoningTokens = output?.reasoningTokens
        )
    }

    private fun String?.orNullWhenBlank(): String? = this?.takeIf { it.isNotBlank() }
}
