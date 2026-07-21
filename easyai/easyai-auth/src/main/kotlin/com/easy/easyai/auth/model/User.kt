package com.easy.easyai.auth.model

/**
 * User entity representing an authenticated user in the system.
 */
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val avatar: String = "avatar-1",
    val email: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Public user info (without sensitive data like password hash).
 * Used for API responses.
 */
data class UserProfile(
    val id: String,
    val username: String,
    val displayName: String,
    val avatar: String,
    val email: String?
) {
    companion object {
        fun from(user: User): UserProfile = UserProfile(
            id = user.id,
            username = user.username,
            displayName = user.displayName,
            avatar = user.avatar,
            email = user.email
        )
    }
}
