package com.easy.easyai.autoconfigure.notification

import com.easy.easyai.core.watch.WatchNotice
import com.easy.easyai.core.watch.WatchNotifier
import com.easy.easyai.notification.NotificationEvent
import com.easy.easyai.notification.NotificationService
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean

/**
 * Delivers watch conclusions into the shared inbox (and, when push is enabled, to the device).
 *
 * A watch has no notification channel of its own: it publishes an informational event whose deep
 * link defaults to `/#/watches/{watchId}`, the path the console's HashRouter serves. A host that
 * mounts the console under another path sets `easyai.watch.notification-url-template` (the
 * `{watchId}` placeholder is replaced); an empty template stores the event without a link. The push
 * banner and the inbox row therefore come from one write, and a deployment without push still shows
 * every finding on the page.
 */
@AutoConfiguration(after = [NotificationAutoConfiguration::class])
@ConditionalOnBean(NotificationService::class)
class WatchNotificationAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(WatchNotifier::class)
    fun watchNotifier(
        notifications: NotificationService,
        @Value("\${easyai.watch.notification-url-template:/#/watches/{watchId}}")
        urlTemplate: String
    ): WatchNotifier = InboxWatchNotifier(notifications, urlTemplate)
}

internal class InboxWatchNotifier(
    private val notifications: NotificationService,
    private val urlTemplate: String = DEFAULT_URL_TEMPLATE
) : WatchNotifier {

    private val logger = LoggerFactory.getLogger(javaClass)

    init {
        // The notification table only accepts a relative deep link; failing at startup beats a row
        // that logs a delivery warning on every firing.
        require(urlTemplate.isBlank() || urlTemplate.startsWith("/")) {
            "easyai.watch.notification-url-template must start with '/' (or be empty), not '$urlTemplate'"
        }
    }

    override suspend fun publish(notice: WatchNotice) {
        try {
            notifications.notify(
                recipients = listOf(notice.userId),
                event = NotificationEvent(
                    type = NOTIFICATION_TYPE,
                    title = notice.title,
                    body = notice.body,
                    url = urlTemplate.takeIf { it.isNotBlank() }
                        ?.replace(WATCH_ID_PLACEHOLDER, notice.watchId),
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

    internal companion object {
        const val NOTIFICATION_TYPE = "watch"
        const val WATCH_ID_PLACEHOLDER = "{watchId}"
        const val DEFAULT_URL_TEMPLATE = "/#/watches/{watchId}"
    }
}
