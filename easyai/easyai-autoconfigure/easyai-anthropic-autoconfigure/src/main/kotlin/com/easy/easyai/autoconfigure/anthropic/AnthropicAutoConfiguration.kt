package com.easy.easyai.autoconfigure.anthropic

import com.easy.easyai.api.config.ChatModelFactory
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for Anthropic protocol support.
 * ChatModelFactory also provides ChatOptionsBuilderFactory functionality.
 */
@AutoConfiguration
@ConditionalOnClass(AnthropicChatOptions::class)
open class AnthropicAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["anthropicChatModelFactory"])
    open fun anthropicChatModelFactory(): ChatModelFactory = AnthropicChatModelFactory()
}
