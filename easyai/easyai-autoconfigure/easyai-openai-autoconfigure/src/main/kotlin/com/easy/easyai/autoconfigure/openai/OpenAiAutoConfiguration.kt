package com.easy.easyai.autoconfigure.openai

import com.easy.easyai.api.config.ChatModelFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * Auto-configuration for OpenAI protocol support.
 * ChatModelFactory also provides ChatOptionsBuilderFactory functionality.
 */
@AutoConfiguration
@ConditionalOnClass(OpenAiChatOptions::class)
open class OpenAiAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["openAiChatModelFactory"])
    open fun openAiChatModelFactory(): ChatModelFactory = OpenAiChatModelFactory()
}
