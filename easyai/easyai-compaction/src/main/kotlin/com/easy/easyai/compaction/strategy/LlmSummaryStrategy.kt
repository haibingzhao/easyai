package com.easy.easyai.compaction.strategy

import com.easy.easyai.compaction.estimator.TokenEstimator
import com.easy.easyai.compaction.model.CompactionContext
import com.easy.easyai.core.model.*
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.model.ChatModel
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.chat.messages.UserMessage as SpringAiUserMessage

/**
 * LLM-based compaction strategy that uses a dedicated model call to generate
 * high-quality semantic summaries.
 *
 * Uses the template defined in the design document:
 * - Goal
 * - Constraints & Preferences
 * - Progress (Done, In Progress, Blocked)
 * - Key Decisions
 * - Next Steps
 * - Critical Context
 * - Relevant Files
 *
 * @param fallbackChatModel ChatModel to use as fallback when no session-specific model is provided.
 *   Recommend a small/fast model like Haiku for cost efficiency.
 */
class LlmSummaryStrategy(
    private val fallbackChatModel: ChatModel? = null
) : CompactionStrategy {

    private val logger = LoggerFactory.getLogger(javaClass)

    companion object {
        private const val SYSTEM_PROMPT = """
            You are an expert conversation summarizer. Your task is to condense a conversation 
            between a user and an AI assistant into a structured summary that preserves all 
            critical context needed to continue the conversation seamlessly.
            
            Follow these rules strictly:
            1. Be concise but comprehensive - capture all important technical details
            2. Preserve file paths, code snippets, and technical specifications exactly
            3. Note any decisions made and their rationale
            4. Identify blocked items and unresolved issues
            5. Extract actionable next steps
        """
    }

    override fun compact(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel?
    ): String = compactWithUsage(messages, context, chatModel).summary

    override fun compactWithUsage(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        chatModel: ChatModel?,
        tokenEstimator: TokenEstimator?
    ): StrategyOutput {
        val conversationText = buildConversationText(messages)
        val prompt = buildCompactionPrompt(conversationText, context.previousSummary)

        logger.debug(
            "Generating LLM summary for {} messages (turn {})",
            messages.size,
            context.currentTurnId
        )

        // Prefer session-specific ChatModel, fallback to constructor-injected one
        val modelToUse = chatModel ?: fallbackChatModel
            ?: return StrategyOutput(generateFallbackSummary(messages, context, "No ChatModel available"))

        val response = try {
            modelToUse.call(prompt)
        } catch (e: Exception) {
            logger.warn("LLM summary generation failed, falling back to placeholder", e)
            return StrategyOutput(generateFallbackSummary(messages, context, "LLM call failed: ${e.message}"))
        }

        val text = response.result?.output?.text
        if (text == null) {
            return StrategyOutput(generateFallbackSummary(messages, context, "Empty response from LLM"))
        }

        val apiUsage = response.metadata?.usage
        val usage = Usage(
            inputTokens = apiUsage?.promptTokens ?: 0,
            outputTokens = apiUsage?.completionTokens ?: 0,
            cacheReadTokens = apiUsage?.cacheReadInputTokens?.toInt() ?: 0,
            cacheWriteTokens = apiUsage?.cacheWriteInputTokens?.toInt() ?: 0
        )
        return StrategyOutput(text, usage)
    }

    private fun buildConversationText(messages: List<EasyAiMessage>): String = buildString {
        messages.forEach { msg ->
            append("[${msg.role.name}]\n")
            msg.content.forEach { block ->
                when (block) {
                    is TextContent -> append(block.text)
                    is ToolCallContent -> append("[Tool call: name=${block.name}, args=${block.arguments}]")
                    is ToolResultContent -> {
                        val preview = block.output.take(500)
                        append("[Tool result: name=${block.toolName}, exitCode=${block.exitCode}, isError=${block.isError}, output=${preview}]")
                    }
                    is ThinkingContent -> append("[Thinking: ${block.thinking}]")
                    else -> {}
                }
            }
            append("\n\n")
        }
    }

    private fun buildCompactionPrompt(
        conversationText: String,
        previousSummary: String?
    ): Prompt {
        val userPrompt = buildString {
            if (previousSummary != null) {
                // Incremental update: merge new conversation into existing summary
                append("Update the anchored summary below using the new conversation history.\n")
                append("Preserve still-true details, remove stale details, and merge in the new facts.\n")
                append("<previous-summary>\n")
                append(previousSummary)
                append("\n</previous-summary>\n\n")
            } else {
                append("Create a new summary from the conversation history below.\n\n")
            }

            append("## Conversation to Summarize\n\n")
            append(conversationText.take(50_000)) // Limit input size

            append("\n\n## Required Output Format\n\n")
            append("""
                ## Goal
                - [Single sentence task summary]

                ## Constraints & Preferences
                - [User constraints, preferences, specifications]

                ## Progress
                ### Done
                - [Completed work]
                ### In Progress
                - [Current work]
                ### Blocked
                - [Blocked items]

                ## Key Decisions
                - [Decisions made and rationale]

                ## Next Steps
                - [Actionable next steps]

                ## Critical Context
                - [Important technical facts, errors, pending questions]

                ## Relevant Files
                - [Relevant file paths or directories]
            """.trimIndent())
        }

        val promptBuilder = Prompt(
            listOf(
                SystemMessage(SYSTEM_PROMPT),
                SpringAiUserMessage(userPrompt)
            )
        )
        return promptBuilder
    }

    private fun generateFallbackSummary(
        messages: List<EasyAiMessage>,
        context: CompactionContext,
        reason: String = "LLM call failed"
    ): String = """
        Context summary (fallback - $reason):
        
        ## Compacted Range
        - Messages compacted: ${messages.size}
        - Turn: ${context.currentTurnId}
        
        Note: Summary generation failed for the LLM service. The conversation contained 
        ${messages.size} messages that have been removed from context to save space.
    """.trimIndent()
}