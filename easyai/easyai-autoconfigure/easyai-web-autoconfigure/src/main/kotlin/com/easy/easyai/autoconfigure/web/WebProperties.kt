package com.easy.easyai.autoconfigure.web

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for EasyAI Web module.
 *
 * Prefix: `easyai.web`
 */
@ConfigurationProperties(prefix = "easyai.web")
data class WebProperties(
    /**
     * Whether to enable web module.
     */
    var enabled: Boolean = true,

    /**
     * SSE timeout in milliseconds.
     */
    var timeout: Long = 300000,

    /**
     * CORS allowed origins.
     */
    var corsAllowedOrigins: String = "*",

    /**
     * CORS allowed methods.
     */
    var corsAllowedMethods: String = "GET,POST,OPTIONS"
)