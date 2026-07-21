package com.easy.easyai.auth

import com.easy.easyai.auth.model.RefreshToken

/**
 * Persistent storage interface for refresh tokens.
 * Tokens are stored as SHA-256 hashes, never as raw values.
 */
interface RefreshTokenStore {

    /**
     * Save a new refresh token record.
     */
    suspend fun save(token: RefreshToken): RefreshToken

    /**
     * Find a refresh token by its SHA-256 hash.
     * @return the token record, or null if not found or already consumed.
     */
    suspend fun findByTokenHash(tokenHash: String): RefreshToken?

    /**
     * Delete a specific refresh token by ID (used during rotation).
     */
    suspend fun delete(id: String)

    /**
     * Delete all refresh tokens for a given user (emergency revocation).
     */
    suspend fun deleteByUserId(userId: String)
}
