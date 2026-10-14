package com.easy.easyai.web.connection

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Read-only network diagnostics.
 *
 * Nothing here feeds a business path. The adapter exists for one reason: which tailnet hop carried a
 * request (punched-direct, a self-hosted relay, a DERP region) is known only to a tailscale daemon,
 * and a browser cannot read one — so the daemon sitting next to the server answers on its behalf.
 */
@ConfigurationProperties(prefix = "easyai.connection")
data class ConnectionProperties(
    val enabled: Boolean = true,
    /** Resolved through `PATH`; a missing binary degrades the adapter instead of failing it. */
    val tailscaleBinary: String = "tailscale",
    /** Background refresh interval for `tailscale status --json`. */
    val statusCacheTtlSeconds: Long = 5,
    /** Hard cap on the CLI call — which never runs on the request path, so this bounds staleness only. */
    val execTimeoutSeconds: Long = 2,
    /**
     * Relay host → display name. A relay node that is itself relayed reports no `Addrs`, so its name
     * cannot always be reverse-looked-up. Dotted keys need brackets in a properties file:
     * `easyai.connection.relay-labels[47.121.24.5]=aliyun-relay`.
     */
    val relayLabels: Map<String, String> = emptyMap(),
)
