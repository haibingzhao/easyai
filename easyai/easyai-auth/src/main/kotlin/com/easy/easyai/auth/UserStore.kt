package com.easy.easyai.auth

import com.easy.easyai.auth.model.User

/**
 * Persistent storage interface for user accounts.
 * Implementations are provided by the repository layer (e.g., R2DBC/H2).
 */
interface UserStore {

    /**
     * Find a user by username **within one application**.
     *
     * Usernames are namespaced per application: the same name may exist in another application on
     * the same database and must not be returned here.
     *
     * @return the user, or null if not found in [ownerApp].
     */
    suspend fun findByUsername(username: String, ownerApp: String): User?

    /**
     * Find a user by ID within one application.
     * @return the user, or null if not found or if the account belongs to another application.
     */
    suspend fun findById(id: String, ownerApp: String): User?

    /**
     * Save a new user. The caller must ensure the username is unique inside [User.ownerApp].
     */
    suspend fun save(user: User): User

    /**
     * Update an existing user (e.g., change avatar, display name). Ownership is immutable.
     */
    suspend fun update(user: User): User

    /**
     * Count accounts in one application.
     */
    suspend fun count(ownerApp: String): Long
}
