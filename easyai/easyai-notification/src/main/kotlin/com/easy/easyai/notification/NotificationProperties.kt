package com.easy.easyai.notification

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the shared notification inbox (`easyai.notification.*`).
 *
 * [source] is the one property a host must set: it is written into every row this deployment
 * publishes and is the filter on every read, so two products sharing one framework database keep
 * separate inboxes. It is taken from configuration and never from a caller, which is what stops a
 * host service from reading another product's notifications.
 */
@ConfigurationProperties(prefix = "easyai.notification")
class NotificationProperties {

    /** Publishing product, e.g. `home` or `trading`. */
    var source: String = DEFAULT_SOURCE

    /** Rows per inbox page when the caller does not ask for a specific size. */
    var listLimit: Int = 50

    /**
     * Whether a persisted notification also hands a message to the Web Push channel.
     *
     * Independent of `easyai.push.enabled`: an operator may want the inbox without banners on the
     * device. Effective push still requires a configured channel and a subscribed device.
     */
    var pushEnabled: Boolean = true

    /** Icon shown in the device notification centre; ignored when push is off. */
    var pushIcon: String = "/icons/mobile-192.png"

    companion object {
        /** Fallback for single-app deployments; sharing one database requires an explicit source. */
        const val DEFAULT_SOURCE = "easyai"
    }
}
