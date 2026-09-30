package com.easy.easyai.autoconfigure.dashscope

import com.alibaba.dashscope.aigc.generation.Generation
import com.easy.easyai.api.config.ChatModelFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Auto-configuration for the DashScope (Aliyun Bailian) native protocol.
 * ChatModelFactory also provides ChatOptionsBuilderFactory functionality.
 */
@AutoConfiguration
@ConditionalOnClass(Generation::class)
open class DashScopeAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = ["dashScopeChatModelFactory"])
    open fun dashScopeChatModelFactory(): ChatModelFactory = DashScopeChatModelFactory()
}
