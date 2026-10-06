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
     * The preset avatar value stored for an account that never picked a picture. The console renders a
     * colour-seeded initial letter for it; anything else in `app_user.avatar` is an uploaded object key
     * or a user-supplied image URL.
     */
    const val DEFAULT_AVATAR = "avatar-1"

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
