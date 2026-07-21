package com.easy.easyai.api.config

import com.easy.easyai.api.model.ModelProviderConfig
import io.micrometer.observation.ObservationRegistry
import org.springframework.ai.chat.model.ChatModel

/**
 * Factory interface for creating ChatModel instances and building ChatOptions based on protocol.
 * Implementations should be provided by protocol-specific autoconfigure modules.
 *
 * This allows dynamic creation of ChatModel instances at runtime based on
 * user-provided model provider configurations (API key, base URL, model ID, etc.).
 *
 * This interface extendss ChatOptionsBuilderFactory to provide a unified factory
 * that can both create ChatModel instances and build ChatOptions for each protocol.
 */
interface ChatModelFactory : ChatOptionsBuilderFactory {
    /**
     * Create a ChatModel instance for the given configuration.
     *
     * @param config The model provider configuration
     * @param observationRegistry the Micrometer observation registry for LLM call tracing.
     *   When provided, Spring AI's built-in GenAI observation spans (model name, token usage,
     *   prompt/completion, finish reasons, etc.) will be emitted to the configured tracing backend.
     *   Defaults to [ObservationRegistry.NOOP] if not specified.
     * @return A ChatModel instance configured for the specified provider
     */
    fun create(
        config: ModelProviderConfig,
        observationRegistry: ObservationRegistry = ObservationRegistry.NOOP
    ): ChatModel
}
