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
    var flywayEnabled: Boolean = true
)
