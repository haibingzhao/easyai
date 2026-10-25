package com.easy.easyai.autoconfigure.push

import com.easy.easyai.push.PushProperties
import com.easy.easyai.push.WebPushSender
import com.easy.easyai.push.WebPushService
import com.easy.easyai.push.ZerodepWebPushSender
import com.easy.easyai.repository.push.PushSubscriptionStore
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Wires the shared Web Push channel and its account-level API.
 *
 * The controller is registered explicitly instead of component-scanned: it lives outside
 * `com.easy.easyai.web` (the package scanned by [com.easy.easyai.autoconfigure.web.WebAutoConfiguration]),
 * and an application without push should not carry an endpoint that cannot work.
 *
 * The whole configuration waits for [PushSubscriptionStore], which is only registered when the
 * R2DBC repository autoconfiguration is active — an app with push on the classpath but no
 * database keeps starting.
 */
@AutoConfiguration(afterName = ["com.easy.easyai.autoconfigure.r2dbc.R2dbcRepositoryAutoConfiguration"])
@ConditionalOnClass(WebPushService::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBean(PushSubscriptionStore::class)
@EnableConfigurationProperties(PushProperties::class)
class PushAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WebPushSender::class)
    fun webPushSender(properties: PushProperties): WebPushSender = ZerodepWebPushSender(properties)

    @Bean
    @ConditionalOnMissingBean(WebPushService::class)
    fun webPushService(
        properties: PushProperties,
        sender: WebPushSender,
        subscriptions: PushSubscriptionStore,
    ): WebPushService = WebPushService(properties, sender, subscriptions)

    @Bean
    @ConditionalOnMissingBean(PushController::class)
    fun pushController(push: WebPushService): PushController = PushController(push)
}
