package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.auth.group.GroupClaims
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.http.HttpStatus
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException

/**
 * Utility to extract the current authenticated user ID from the Spring Security
 * reactive context. Works inside `mono { }` coroutine blocks because
 * kotlinx-coroutines-reactor propagates the Reactor context.
 *
 * Returns [AuthConstants.SYSTEM_USER_ID] when no security context is present
 * (i.e., when auth is disabled).
 *
 * Throws ResponseStatusException(401) when a security context exists but the
 * principal cannot be resolved (malformed authentication).
 */
suspend fun getCurrentUserId(): String {
    val context = ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()
        ?: return AuthConstants.SYSTEM_USER_ID
    val principal = context.authentication?.principal as? String
    return principal
        ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required")
}

/**
 * The group claims [JwtAuthenticationFilter] attached to the current authentication, or an empty
 * (group-less) set when there is no security context (auth disabled) or the token carried no group.
 */
suspend fun currentGroupClaims(): GroupClaims {
    val context = ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()
        ?: return GroupClaims()
    return context.authentication?.details as? GroupClaims ?: GroupClaims()
}

/**
 * The full read-visibility owner set for the current request: the caller, any group bucket resolved
 * at sign-in, and the shared `system` layer. Always includes [getCurrentUserId] and
 * [AuthConstants.SYSTEM_USER_ID], so stores can pass it straight to `UserScope.filterOwners(column, owners)`.
 *
 * An auth-disabled deployment writes no security context at all, so [getCurrentUserId] takes its
 * "no context" branch and returns `system` (it does NOT throw) — making this `{system}`. An
 * authenticated, group-less login yields `{self, system}`. Both match the pre-group
 * `UserScope.filter(column, userId)` semantics exactly. The 401 path only fires when a context
 * exists but its principal is malformed — a genuine auth failure, not the auth-disabled case.
 */
suspend fun currentOwners(): Set<String> {
    val userId = getCurrentUserId()
    val claims = currentGroupClaims()
    return buildSet {
        add(userId)
        addAll(claims.owners)
        add(AuthConstants.SYSTEM_USER_ID)
    }
}

/** The shared-asset bucket id for the active group, or null when this login has no group. */
suspend fun currentGroupUserId(): String? = currentGroupClaims().groupUserId

/** Whether the current login may write group-owned assets (i.e. is acting as the group owner). */
suspend fun isGroupOwner(): Boolean = currentGroupClaims().isGroupOwner

/** The id of the group this login is acting under, or null when group-less. */
suspend fun currentGroupId(): String? = currentGroupClaims().groupId

/**
 * Owners *without* the shared `system` layer: the caller plus their group bucket. For tables that
 * historically never folded in `system` (auxiliary model choices, MCP server configs), this adds
 * group sharing while preserving their exact pre-group visibility — a plain `{self}` when group-less.
 * Insertion order is self-then-group, so a fan-out read keeps the personal row over the group's.
 */
suspend fun currentGroupOwners(): Set<String> {
    val userId = getCurrentUserId()
    return buildSet {
        add(userId)
        addAll(currentGroupClaims().owners)
    }
}
