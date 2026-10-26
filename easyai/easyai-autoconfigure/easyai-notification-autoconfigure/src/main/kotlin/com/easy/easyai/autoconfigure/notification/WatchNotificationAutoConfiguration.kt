package com.easy.easyai.autoconfigure.notification

import com.easy.easyai.core.watch.WatchNotice
import com.easy.easyai.core.watch.WatchNotifier
import com.easy.easyai.notification.NotificationEvent
import com.easy.easyai.notification.NotificationService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Delivers watch conclusions into the shared inbox (and, when push is enabled, to the device).
 *
 * A watch has no notification channel of its own: it publishes an informational event whose deep
 * link is `/#/watches/{id}`, which is exactly the path the console's HashRouter serves. The push
 * banner and the inbox row therefore come from one write, and a deployment without push still shows
 * every finding on the page.
 */
@AutoConfiguration(after = [NotificationAutoConfiguration::class])
@ConditionalOnBean(NotificationService::class)
class WatchNotificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WatchNotifier::class)
    fun watchNotifier(notifications: NotificationService): WatchNotifier =
        InboxWatchNotifier(notifications)
}

internal class InboxWatchNotifier(
    private val notifications: NotificationService
) : WatchNotifier {

    private val logger = LoggerFactory.getLogger(javaClass)

    override suspend fun publish(notice: WatchNotice) {
        try {
            notifications.notify(
                recipients = listOf(notice.userId),
                event = NotificationEvent(
                    type = NOTIFICATION_TYPE,
                    title = notice.title,
                    body = notice.body,
                    url = "/#/watches/${notice.watchId}",
                    scopeType = "watch",
                    scopeId = notice.watchId,
                    scopeName = notice.title,
                    dedupeKey = notice.dedupeKey
                )
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            // The finding is already persisted; a broken inbox must not fail the run.
            logger.warn("Watch notice for {} was not delivered: {}", notice.watchId, error.message)
        }
    }

    private companion object {
        const val NOTIFICATION_TYPE = "watch"
    }
}
