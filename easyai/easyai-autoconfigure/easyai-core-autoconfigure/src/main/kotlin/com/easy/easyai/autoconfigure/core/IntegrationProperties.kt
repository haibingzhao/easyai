package com.easy.easyai.autoconfigure.core

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Deployment-wide third-party integration settings.
 *
 * Prefix: easyai.integrations
 *
 * Optional and unset by default. When any field is provided, integrations become a deployment-wide
 * STATIC layer: the values pin the effective API keys for every user, `~/.easyai/integrations.json`
 * is bypassed, and the Settings → Integrations form is rendered read-only. This is the B/S
 * multi-tenant mode — keys then live in config, never in a file a member could edit or read back.
 * Leaving everything blank keeps the historical file / environment-variable behavior.
 */
@ConfigurationProperties(prefix = "easyai.integrations")
data class IntegrationProperties(
    /** EXA web-search API key. Server-side only, never echoed to the console. */
    var exaApiKey: String = "",

    /** Parallel web-search API key. Server-side only, never echoed to the console. */
    var parallelApiKey: String = "",

    /** Preferred web-search provider (`exa` or `parallel`). */
    var websearchProvider: String = ""
) {
    /** True when a deployment-wide STATIC layer is configured (any integration value is pinned). */
    fun isStatic(): Boolean =
        exaApiKey.isNotBlank() || parallelApiKey.isNotBlank() || websearchProvider.isNotBlank()
}
