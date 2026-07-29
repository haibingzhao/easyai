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
     */
    fun build(
        config: ModelProviderConfig,
        toolCallbacks: List<ToolCallback>
    ): ChatOptions
}