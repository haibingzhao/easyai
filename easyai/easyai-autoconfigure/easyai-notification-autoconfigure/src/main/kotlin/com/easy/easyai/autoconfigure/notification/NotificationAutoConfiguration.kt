package com.easy.easyai.autoconfigure.notification

import com.easy.easyai.notification.NotificationActionHandler
import com.easy.easyai.notification.NotificationProperties
import com.easy.easyai.notification.NotificationService
import com.easy.easyai.push.PushNotifier
import com.easy.easyai.repository.notification.NotificationStore
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean

/**
 * Wires the shared notification inbox and its account-level API.
 *
 * The whole configuration waits for [NotificationStore], which the R2DBC auto-configuration
 * registers only when a database is configured: an application with the module on its classpath but
 * no persistence keeps starting, just without an inbox.
 *
 * Handlers and the push channel are both collected through [ObjectProvider] rather than injected as
 * a `List`/plain bean: a host may legitimately have **no** action type yet, and the Web Push module
 * is optional. Injecting either as a required type would make start-up depend on how many optional
 * features a product happens to use.
 */
@AutoConfiguration(afterName = ["com.easy.easyai.autoconfigure.r2dbc.R2dbcRepositoryAutoConfiguration"])
@ConditionalOnClass(NotificationService::class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnBean(NotificationStore::class)
@EnableConfigurationProperties(NotificationProperties::class)
class NotificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(NotificationService::class)
    fun notificationService(
        properties: NotificationProperties,
        store: NotificationStore,
        handlers: ObjectProvider<NotificationActionHandler>,
        push: ObjectProvider<PushNotifier>,
    ): NotificationService = NotificationService(
        store = store,
        properties = properties,
        push = push.getIfAvailable(),
        handlers = handlers.stream().toList(),
    )

    @Bean
    @ConditionalOnMissingBean(NotificationController::class)
    fun notificationController(notifications: NotificationService): NotificationController =
        NotificationController(notifications)
}
