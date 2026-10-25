package com.easy.easyai.autoconfigure.push

import com.easy.easyai.push.WebPushSender
import com.easy.easyai.push.WebPushService
import com.easy.easyai.repository.push.PushSubscriptionStore
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

class PushAutoConfigurationTest {

    @Test
    fun `the channel and its API are wired when a store is present`() {
        runner().run { context ->
            assertThat(context).hasSingleBean(WebPushSender::class.java)
            assertThat(context).hasSingleBean(WebPushService::class.java)
            assertThat(context).hasSingleBean(PushController::class.java)
        }
    }

    @Test
    fun `without a subscription store the whole channel stays absent`() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PushAutoConfiguration::class.java))
            .withPropertyValues("easyai.push.enabled=false")
            .run { context ->
                assertThat(context).doesNotHaveBean(WebPushService::class.java)
                assertThat(context).doesNotHaveBean(PushController::class.java)
            }
    }

    @Test
    fun `a custom sender wins over the built-in transport`() {
        runner()
            .withUserConfiguration(CustomSenderConfig::class.java)
            .run { context ->
                assertThat(context).getBean(WebPushSender::class.java).isInstanceOf(FakeSender::class.java)
            }
    }

    private fun runner(): ReactiveWebApplicationContextRunner = ReactiveWebApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(PushAutoConfiguration::class.java))
        .withUserConfiguration(StoreConfig::class.java)
        .withPropertyValues("easyai.push.enabled=false")

    @Configuration(proxyBeanMethods = false)
    private class StoreConfig {
        @Bean
        fun pushSubscriptionStore(): PushSubscriptionStore = MemoryStore()
    }

    @Configuration(proxyBeanMethods = false)
    private class CustomSenderConfig {
        @Bean
        fun webPushSender(): WebPushSender = FakeSender()
    }
}
