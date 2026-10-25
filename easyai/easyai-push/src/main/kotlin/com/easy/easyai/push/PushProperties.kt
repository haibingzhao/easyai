package com.easy.easyai.push

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration of the shared Web Push channel (`easyai.push.*`).
 *
 * The keys are read lazily by [ZerodepWebPushSender]; a missing or malformed key pair never
 * aborts start-up, it only turns the channel off (`enabled = property && keys loaded`).
 *
 * Defaults are deliberately off: push is an opt-in capability, so a deployment that does not
 * configure VAPID keys neither loads the key files nor logs a warning.
 */
@ConfigurationProperties(prefix = "easyai.push")
class PushProperties {

    /** Master switch; effective enablement additionally requires a loadable VAPID key pair. */
    var enabled: Boolean = false

    /** PKCS#8 PEM (`BEGIN PRIVATE KEY`) of the VAPID P-256 key pair. */
    var vapidPrivateKeyPath: String = ""

    /** X.509 PEM (`BEGIN PUBLIC KEY`) with the *uncompressed* public point. */
    var vapidPublicKeyPath: String = ""

    /** VAPID `sub` claim: a `mailto:` or `https:` URI identifying the operator. */
    var subject: String = ""

    /** How long the push service may retain an undelivered message, in seconds. */
    var ttlSeconds: Long = 86_400

    /**
     * Endpoint host allowlist. Deliberately a closed list rather than a wildcard: this is the
     * SSRF boundary, and a mistyped wildcard would silently open it.
     *
     * The default only covers Apple's push service (iOS Home-Screen apps). A desktop browser
     * subscription uses FCM and is rejected as unsupported unless `fcm.googleapis.com` is added.
     */
    var allowedHosts: List<String> = listOf("web.push.apple.com")
}
