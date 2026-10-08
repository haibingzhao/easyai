package com.easy.easyai.repository.database

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.repository.database.UserScope.filter
import org.jetbrains.exposed.v1.core.Column
import org.jetbrains.exposed.v1.core.Op
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.core.inList
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
     * Build a filter condition matching rows owned by any id in [owners] — the N-value form used for
     * group sharing, where a request sees its own rows, its group bucket's rows, and the system rows.
     *
     * Unlike [filter] this does NOT add [SYSTEM_USER_ID] implicitly: the caller supplies the complete
     * visibility set (`SecurityUtils.currentOwners()` already unions in self and system). Duplicates
     * are collapsed so a caller passing `{self, self, system}` yields a clean `IN (...)`.
     *
     * [owners] must be non-empty — `currentOwners()` always is. An empty set is not a supported input:
     * it renders an `IN ()` predicate that matches nothing, which is never what a caller means here.
     */
    fun filter(column: Column<String>, owners: Collection<String>): Op<Boolean> =
        column inList owners.distinct()

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
     * In-memory equivalent of the N-value [filter]: true if [dataOwnerId] is any of [owners].
     * The caller supplies the full visibility set (system included when it should match).
     */
    fun matches(dataOwnerId: String, owners: Collection<String>): Boolean =
        dataOwnerId in owners

    /**
     * In-memory equivalent of [filter] for checking ownership after loading a row.
     * Returns true if the data is owned by the given user.
     * (excludes system user data).
     */
    fun matchesStrict(dataOwnerId: String, userId: String): Boolean =
        dataOwnerId == userId
}
