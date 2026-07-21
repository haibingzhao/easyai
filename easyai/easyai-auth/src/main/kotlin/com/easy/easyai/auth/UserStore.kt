package com.easy.easyai.auth

import com.easy.easyai.auth.model.User

/**
 * Persistent storage interface for user accounts.
 * Implementations are provided by the repository layer (e.g., R2DBC/H2).
 */
interface UserStore {

    /**
     * Find a user by unique username.
     * @return the user, or null if not found.
     */
    suspend fun findByUsername(username: String): User?

    /**
     * Find a user by ID.
     * @return the user, or null if not found.
     */
    suspend fun findById(id: String): User?

    /**
     * Save a new user. The caller must ensure username uniqueness.
     */
    suspend fun save(user: User): User

    /**
     * Update an existing user (e.g., change avatar, display name).
     */
    suspend fun update(user: User): User

    /**
     * Count total users in the system.
     */
    suspend fun count(): Long
}
