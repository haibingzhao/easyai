package com.easy.easyai.web.security

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Configuration properties for EasyAI authentication.
 *
 * When easyai.auth.enabled=false (default for local dev), all routes are public
 * and userId defaults to "system".
 * When easyai.auth.enabled=true, Bearer token authentication is required
 * on all routes except auth endpoints and health check.
 */
@ConfigurationProperties(prefix = "easyai.auth")
data class AuthProperties(
    // Enable or disable authentication globally.
    val enabled: Boolean = false,
    // Allow new user registration.
    val registrationEnabled: Boolean = true,
    // Access token TTL in seconds (default: 2 hours).
    val accessTokenExpirationSeconds: Long = 7200,
    // Refresh token TTL in seconds (default: 180 days).
    val refreshTokenExpirationSeconds: Long = 15552000,
    // CORS allowed origins for frontend.
    val corsAllowedOrigins: List<String> = listOf("http://localhost:5173"),
    // PEM file path for RSA private key (production). If empty, dev key pair is generated.
    val privateKeyPath: String = "",
    // PEM file path for RSA public key (production). If empty, dev key pair is generated.
    val publicKeyPath: String = ""
)
