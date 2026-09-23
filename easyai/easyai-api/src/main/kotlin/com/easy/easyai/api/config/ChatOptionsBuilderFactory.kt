package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelProviderInfo.Protocol
import org.springframework.ai.chat.prompt.ChatOptions
import org.springframework.ai.tool.ToolCallback

/**
 * Factory interface for building ChatOptions based on protocol.
 * Implementations should be provided by protocol-specific autoconfigure modules.
 */
interface ChatOptionsBuilderFactory {
    /**
     * Check if this factory supports the given protocol.
     */
    fun supports(protocol: Protocol): Boolean

    /**
     * Build ChatOptions for the given configuration.
     * @param config The model provider configuration
     * @param toolCallbacks The tool callbacks to register
     * @param outputSchema JSON schema to enforce at the API level for this turn, if any.
     *   Implementations must apply it only when the model's declared
     *   `capabilities.structuredOutput` (null = JSON_SCHEMA) supports it AND the protocol
     *   can express it; otherwise return options without API-level enforcement so the run
     *   degrades to prompt-based validation (OutputSchemaCompletionCheck).
     */
    fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>,
        outputSchema: String? = null
    ): ChatOptions
}