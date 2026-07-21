package com.easy.easyai.auth.model

/**
 * Refresh token entity. The database stores the SHA-256 hash of the token,
 * never the raw token value.
 */
data class RefreshToken(
    val id: String,
    val userId: String,
    val tokenHash: String,
    val expiresAt: Long,
    val createdAt: Long = System.currentTimeMillis()
)
