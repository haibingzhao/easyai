package com.easy.easyai.auth

/**
 * Authentication constants shared across the auth system.
 */
object AuthConstants {

    /**
     * System user ID used for default agents, built-in tools, and seed data.
     * Data owned by the system user is visible to all authenticated users.
     */
    const val SYSTEM_USER_ID = "system"

    /**
     * HTTP header name for the Bearer token.
     */
    const val AUTHORIZATION_HEADER = "Authorization"

    /**
     * Bearer token prefix.
     */
    const val BEARER_PREFIX = "Bearer "

    /**
     * Refresh token cookie name.
     */
    const val REFRESH_TOKEN_COOKIE = "easyai_refresh_token"
}
