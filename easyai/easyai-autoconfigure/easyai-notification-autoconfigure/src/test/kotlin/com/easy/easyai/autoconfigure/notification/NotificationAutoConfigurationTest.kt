package com.easy.easyai.autoconfigure.notification

import com.easy.easyai.notification.NotificationActionHandler
import com.easy.easyai.notification.NotificationConflictException
import com.easy.easyai.notification.NotificationDecision
import com.easy.easyai.notification.NotificationEvent
import com.easy.easyai.notification.NotificationNotFoundException
import com.easy.easyai.notification.NotificationService
import com.easy.easyai.notification.NotificationValidationException
import com.easy.easyai.repository.notification.Notification
import com.easy.easyai.repository.notification.NotificationActionStatus
import com.easy.easyai.repository.notification.NotificationStore
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * What a host gets when it adds the module: the beans appear when a database is configured, the
 * optional collaborators (business handlers, the push channel) are collected without being
 * required, and nothing at all is wired when the store is missing.
 */
class NotificationAutoConfigurationTest {

    private val event = NotificationEvent(
        type = "owner_transfer",
        title = "把「家」转让给你",
        scopeType = "family",
        scopeId = "f1",
        actorUserId = "owner",
    )

    @Test
    fun `the inbox and its API are wired when a store is present`() {
        runner().run { context ->
            assertThat(context).hasSingleBean(NotificationService::class.java)
            assertThat(context).hasSingleBean(NotificationController::class.java)
        }
    }

    @Test
    fun `without a store the whole feature stays absent`() {
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .run { context ->
                assertThat(context).doesNotHaveBean(NotificationService::class.java)
                assertThat(context).doesNotHaveBean(NotificationController::class.java)
            }
    }

    @Test
    fun `a service without the push module still publishes`() {
        runner().run { context ->
            val published = runBlocking {
                context.getBean(NotificationService::class.java).notify(listOf("a"), event)
            }
            assertThat(published).hasSize(1)
        }
    }

    @Test
    fun `the configured source is written and read`() {
        runner("easyai.notification.source=trading").run { context ->
            val service = context.getBean(NotificationService::class.java)

            val published = runBlocking { service.notify(listOf("a"), event) }
            assertThat(published.single().source).isEqualTo("trading")
            // Another product's row is not even addressable by id.
            assertThatThrownBy { runBlocking { service.markRead("elsewhere", published.single().id) } }
                .isInstanceOf(NotificationNotFoundException::class.java)
        }
    }

    @Test
    fun `a duplicate live request is refused before it reaches the store`() {
        runner().run { context ->
            val service = context.getBean(NotificationService::class.java)
            runBlocking { service.request("target", event) }

            assertThatThrownBy { runBlocking { service.request("other", event) } }
                .isInstanceOf(NotificationConflictException::class.java)
        }
    }

    @Test
    fun `a host handler is collected and answers its own type`() {
        runner().withUserConfiguration(HandlerConfig::class.java).run { context ->
            val service = context.getBean(NotificationService::class.java)
            val copies = runBlocking { service.request("target", event) }

            val view = runBlocking { service.respond("target", copies.first().id, NotificationDecision.ACCEPT) }

            assertThat(view.actionStatus).isEqualTo(NotificationActionStatus.ACCEPTED)
            assertThat(HandlerConfig.accepted).containsExactly(copies.first().eventId)
        }
    }

    @Test
    fun `without any handler an action type is rejected instead of silently accepted`() {
        runner().run { context ->
            val service = context.getBean(NotificationService::class.java)
            val copies = runBlocking { service.request("target", event) }

            assertThatThrownBy {
                runBlocking { service.respond("target", copies.first().id, NotificationDecision.ACCEPT) }
            }.isInstanceOf(NotificationValidationException::class.java)
        }
    }

    private fun runner(vararg properties: String): ReactiveWebApplicationContextRunner =
        ReactiveWebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(NotificationAutoConfiguration::class.java))
            .withUserConfiguration(StoreConfig::class.java)
            .withPropertyValues(*properties)

    @Configuration(proxyBeanMethods = false)
    private class StoreConfig {
        @Bean
        fun notificationStore(): NotificationStore = MemoryNotificationStore()
    }

    @Configuration(proxyBeanMethods = false)
    private class HandlerConfig {
        @Bean
        fun transferHandler(): NotificationActionHandler = object : NotificationActionHandler {
            override val type = "owner_transfer"
            override suspend fun onAccepted(notification: Notification, context: Any?) {
                accepted += notification.eventId
            }
        }

        companion object {
            val accepted = mutableListOf<String>()
        }
    }
}
