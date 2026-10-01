package com.easy.easyai.autoconfigure.dashscope

import org.springframework.ai.chat.messages.AssistantMessage
import org.springframework.ai.chat.messages.Message
import org.springframework.ai.chat.messages.MessageType
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.ToolResponseMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.content.Media
import org.slf4j.LoggerFactory
import java.util.Base64
import java.net.URI
import java.net.URL

/** A tool call as it must appear on the DashScope wire. */
internal data class DashScopeToolCallSpec(
    val id: String,
    val name: String,
    val arguments: String
)

/**
 * Protocol-neutral view of one conversation turn, shared by the text and the multimodal
 * request builders so both render from a single conversion instead of duplicating the
 * Spring AI message walk.
 */
internal data class DashScopeMessageSpec(
    val role: String,
    val text: String?,
    val images: List<String> = emptyList(),
    val toolCalls: List<DashScopeToolCallSpec> = emptyList(),
    val toolCallId: String? = null,
    val toolName: String? = null
)

/**
 * Converts Spring AI prompt messages into [DashScopeMessageSpec].
 *
 * DashScope accepts only one system message, at the front, so all system text is merged;
 * reasoning content is deliberately never replayed because `preserve_thinking` requires the
 * historical chain to be returned byte-for-byte in order.
 */
internal object DashScopeMessageConverter {

    private val logger = LoggerFactory.getLogger(DashScopeMessageConverter::class.java)

    fun convert(messages: List<Message>, supportsVision: Boolean): List<DashScopeMessageSpec> {
        val specs = mutableListOf<DashScopeMessageSpec>()
        val systemText = StringBuilder()

        for (message in messages) {
            when (message) {
                is SystemMessage -> appendSystem(systemText, message.text)
                is UserMessage -> specs.add(userSpec(message, supportsVision))
                is AssistantMessage -> specs.add(assistantSpec(message))
                is ToolResponseMessage -> message.responses.forEach { response ->
                    specs.add(
                        DashScopeMessageSpec(
                            role = "tool",
                            text = response.responseData(),
                            toolCallId = response.id(),
                            toolName = response.name().takeIf { it.isNotBlank() }
                        )
                    )
                }
                else -> specs.add(
                    DashScopeMessageSpec(role = roleFor(message.messageType), text = message.text)
                )
            }
        }

        val system = systemText.toString().takeIf { it.isNotEmpty() }
        return if (system == null) specs else listOf(DashScopeMessageSpec("system", system)) + specs
    }

    private fun userSpec(message: UserMessage, supportsVision: Boolean): DashScopeMessageSpec {
        if (message.media.isEmpty()) return DashScopeMessageSpec("user", message.text)

        if (!supportsVision) {
            logger.warn(
                "Dropping {} attachment(s): the DashScope model does not declare vision support",
                message.media.size
            )
            return DashScopeMessageSpec("user", message.text)
        }
        val images = message.media.mapNotNull { media -> mediaToImageUrl(media) }
        return DashScopeMessageSpec("user", message.text, images)
    }

    private fun assistantSpec(message: AssistantMessage): DashScopeMessageSpec = DashScopeMessageSpec(
        role = "assistant",
        text = message.text ?: "",
        toolCalls = message.toolCalls.map { call ->
            DashScopeToolCallSpec(id = call.id(), name = call.name(), arguments = call.arguments())
        }
    )

    private fun appendSystem(target: StringBuilder, text: String?) {
        if (text.isNullOrEmpty()) return
        if (target.isNotEmpty()) target.append("\n\n")
        target.append(text)
    }

    private fun roleFor(type: MessageType): String = when (type) {
        MessageType.USER -> "user"
        MessageType.ASSISTANT -> "assistant"
        MessageType.SYSTEM -> "system"
        MessageType.TOOL -> "tool"
    }

    /**
     * DashScope accepts public HTTPS image URLs natively; inline bytes must travel as a data URL.
     */
    private fun mediaToImageUrl(media: Media): String? {
        return when (val data = media.data) {
            is URI -> data.toString()
            is URL -> data.toString()
            is String -> data
            else -> runCatching {
                "data:${media.mimeType};base64,${Base64.getEncoder().encodeToString(media.dataAsByteArray)}"
            }.onFailure {
                logger.warn("Could not inline media {}: {}", media.name, it.message)
            }.getOrNull()
        }
    }
}
