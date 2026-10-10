package com.easy.easyai.autoconfigure.anthropic

import com.easy.easyai.api.config.ChatModelFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for Anthropic protocol support.
 * ChatModelFactory also provides ChatOptionsBuilderFactory functionality.
 */
// Guard on the official SDK (not this module's own class, which is always present) so the
// auto-configuration is skipped cleanly when a consumer excludes anthropic-java.
@AutoConfiguration
@ConditionalOnClass(name = ["com.anthropic.client.okhttp.AnthropicOkHttpClient"])
open class AnthropicAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["anthropicChatModelFactory"])
    open fun anthropicChatModelFactory(): ChatModelFactory = AnthropicChatModelFactory()
}
