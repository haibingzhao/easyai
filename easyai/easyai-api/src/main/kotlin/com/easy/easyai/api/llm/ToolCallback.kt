package com.easy.easyai.api.llm

/**
 * A tool descriptor handed to a [ChatModel] for the current turn. This replaces
 * Spring AI's `tool.ToolCallback`, but only as a passive carrier of the tool's
 * name/description/JSON-schema — easyai's ReAct loop executes tools itself and never invokes a
 * callback, so there is no `call()` here (unlike the Spring AI interface).
 */
data class ToolCallback(
    val name: String,
    val description: String,
    val inputSchema: String
)
