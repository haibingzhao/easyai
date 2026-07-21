package com.easy.easyai.repository.database

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.repository.database.UserScope.filter
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.or

/**
 * Data isolation helper for user-scoped queries.
 *
 * Usage:
 * ```kotlin
 * .where { UserScope.filter(AgentTable.userId, currentUserId) }
 * // generates: WHERE user_id = :currentUserId OR user_id = 'system'
 * ```
 */
object UserScope {

    /**
     * System user ID for default agents, built-in tools, and seed data.
     * Data owned by the system user is visible to all authenticated users.
     * Delegates to [AuthConstants.SYSTEM_USER_ID] to avoid duplicate definitions.
     */
    val SYSTEM_USER_ID: String get() = AuthConstants.SYSTEM_USER_ID

    /**
     * Build a filter condition that matches rows owned by the given user
     * OR owned by the system user (shared/default data).
     */
    fun filter(column: Column<String>, userId: String): Op<Boolean> =
        (column eq userId) or (column eq SYSTEM_USER_ID)

    /**
     * Build a filter condition that matches rows owned strictly by the given user
     * (excludes system user data).
     */
    fun filterStrict(column: Column<String>, userId: String): Op<Boolean> =
        column eq userId

    /**
     * In-memory equivalent of [filter] for checking ownership after loading a row.
     * Returns true if the data is owned by the given user or by the system user.
     */
    fun matches(dataOwnerId: String, userId: String): Boolean =
        dataOwnerId == userId || dataOwnerId == SYSTEM_USER_ID
    /**
     * In-memory equivalent of [filter] for checking ownership after loading a row.
     * Returns true if the data is owned by the given user.
     * (excludes system user data).
     */
    fun matchesStrict(dataOwnerId: String, userId: String): Boolean =
        dataOwnerId == userId
}
