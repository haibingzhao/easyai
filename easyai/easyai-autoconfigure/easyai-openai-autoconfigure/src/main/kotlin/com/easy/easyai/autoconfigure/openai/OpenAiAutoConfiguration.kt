package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.config.ChatModelFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for OpenAI protocol support.
 * ChatModelFactory also provides ChatOptionsBuilderFactory functionality.
 */
// Guard on the official SDK (not this module's own class, which is always present) so the
// auto-configuration is skipped cleanly when a consumer excludes openai-java.
@AutoConfiguration
@ConditionalOnClass(name = ["com.openai.client.okhttp.OpenAIOkHttpClientAsync"])
open class OpenAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["openAiChatModelFactory"])
    open fun openAiChatModelFactory(): ChatModelFactory = OpenAiChatModelFactory()
}
