package com.easy.easyai.core.message

import com.easy.easyai.core.model.AssistantMessage
import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.ThinkingContent

/**
 * Send-time projection gating reasoning replay: whether persisted [ThinkingContent] blocks are
 * sent back as assistant-turn history.
 *
 * The transcript always keeps originals. When replay is disabled, blocks are dropped before both
 * the compaction measurement view and the prompt view, so token estimates match what actually
 * goes on the wire — thinking is the single largest invisible context cost per turn. When
 * enabled, [DefaultMessageConverter] maps the blocks onto
 * [com.easy.easyai.api.llm.AssistantMessage.thinkingBlocks] and only the Anthropic adapter
 * replays them (signed thinking blocks); OpenAI has no replay field and DashScope deliberately
 * never replays reasoning.
 */
object ThinkingHistoryProjection {

    fun project(messages: List<EasyAiMessage>, enabled: Boolean): List<EasyAiMessage> {
        if (enabled) return messages
        return messages.map { msg ->
            val content = (msg as? AssistantMessage)?.content ?: return@map msg
            if (content.none { it is ThinkingContent }) msg
            else msg.copy(content = content.filterNot { it is ThinkingContent })
        }
    }
}
