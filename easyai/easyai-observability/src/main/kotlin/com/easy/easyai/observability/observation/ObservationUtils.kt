package com.easy.easyai.observability.observation

import com.easy.easyai.core.agent.ChatSession
import org.slf4j.LoggerFactory

/**
 * Utility functions for observation handling.
 */
object ObservationUtils {

    private val log = LoggerFactory.getLogger(ObservationUtils::class.java)

    /**
     * Truncates a string to the specified max length, appending "..." if truncated.
     *
     * @param value the string to truncate
     * @param maxLength maximum length (default 4000)
     * @return truncated string or empty string if null
     */
    fun truncate(value: String?, maxLength: Int = 4000): String {
        if (value == null) return ""
        return if (value.length > maxLength) {
            value.substring(0, maxLength) + "..."
        } else {
            value
        }
    }

    /**
     * Extracts goal name from chat session.
     *
     * @param session the chat session
     * @return goal name or empty string
     */
    fun extractGoalName(session: ChatSession): String {
        // TODO: Implement based on EasyAI's goal tracking mechanism
        return session.id
    }

    /**
     * Gets a snapshot of the conversation state.
     *
     * @param session the chat session
     * @return conversation snapshot as string
     */
    fun getConversationSnapshot(session: ChatSession): String {
        // TODO: Implement based on EasyAI's message history
        return ""
    }

    /**
     * Formats plan steps for observation attributes.
     *
     * @param actions list of planned actions
     * @return formatted string
     */
    fun formatPlanSteps(actions: List<String>): String {
        return actions.joinToString("\n") { action -> "- $action" }
    }

    /**
     * Safely converts an object to string with truncation.
     *
     * @param obj the object to convert
     * @param maxLength maximum length
     * @return truncated string representation
     */
    fun safeToString(obj: Any?, maxLength: Int = 4000): String {
        return if (obj != null) {
            truncate(obj.toString(), maxLength)
        } else {
            ""
        }
    }
}
