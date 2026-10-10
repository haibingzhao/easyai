package com.easy.easyai.api.llm

/**
 * A chat request: the ordered [instructions] to send plus the [options] for this turn. Replaces
 * Spring AI's `chat.prompt.Prompt`. [options] is null when the caller relies on the
 * [ChatModel]'s own defaults.
 */
data class Prompt(
    val instructions: List<Message>,
    val options: ChatOptions? = null
) {
    constructor(contents: String, options: ChatOptions? = null) :
        this(listOf(UserMessage(contents)), options)
}
