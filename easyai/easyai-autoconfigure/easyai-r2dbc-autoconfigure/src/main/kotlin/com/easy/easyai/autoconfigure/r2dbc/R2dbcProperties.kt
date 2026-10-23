package com.easy.easyai.autoconfigure.r2dbc

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for R2DBC storage (H2, PostgreSQL, etc.).
 *
 * Example YAML configuration:
 * ```yaml
 * easyai:
 *   r2dbc:
 *     enabled: true
 *     url: "r2dbc:postgresql://localhost:5432/easyai"
 *     username: postgres
 *     password: ""
 * ```
 */
@ConfigurationProperties(prefix = "easyai.r2dbc")
data class R2dbcProperties(
    var enabled: Boolean = true,
    var url: String = "r2dbc:h2:mem:///easyai;MODE=MYSQL",
    var username: String = "sa",
    var password: String = "",
    /** Enable Flyway versioned migration for persistent DBs. Set false to fall back to SchemaUtils. */
    var flywayEnabled: Boolean = true,
    /**
     * TCP connect timeout appended to PostgreSQL URLs, in seconds (0 disables the rewrite).
     * Without it an unreachable host can hold a request for the driver's own default.
     */
    var connectTimeoutSeconds: Int = 5,
    /** Probe the database periodically and log connectivity changes. */
    var healthCheckEnabled: Boolean = true,
    /** Interval between health probes, in milliseconds. */
    var healthCheckIntervalMs: Long = 30_000,
    /** Upper bound for a single health probe, in milliseconds. */
    var healthCheckTimeoutMs: Long = 5_000
)
