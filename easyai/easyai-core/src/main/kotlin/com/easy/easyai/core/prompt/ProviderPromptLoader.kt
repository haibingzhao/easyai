package com.easy.easyai.core.prompt

/**
 * Loads provider-specific prompt templates based on protocol.
 */
interface ProviderPromptLoader {
    /**
     * Get the prompt template for the given protocol.
     * @param protocol e.g. "OPENAI", "ANTHROPIC"
     * @return prompt template text
     */
    fun getPromptForProtocol(protocol: String): String
}