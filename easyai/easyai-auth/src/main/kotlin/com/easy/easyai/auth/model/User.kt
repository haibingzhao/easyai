package com.easy.easyai.auth.model

import com.easy.easyai.auth.AuthConstants

/**
 * User entity representing an authenticated user in the system.
 */
data class User(
    val id: String,
    val username: String,
    val displayName: String,
    val passwordHash: String,
    val avatar: String = AuthConstants.DEFAULT_AVATAR,
    val email: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * Public user info (without sensitive data like password hash).
 * Used for API responses.
 *
 * [avatar] is the persisted reference as stored: the preset [AuthConstants.DEFAULT_AVATAR], an uploaded
 * `avatars/{owner}/{uuid}.png` object key, or a user-supplied http(s) image URL. It is deliberately not
 * resolved here — presigned links expire, so clients turn the key into `GET /api/media/file?key=` themselves.
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
