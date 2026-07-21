package com.easy.easyai.web.security

import com.easy.easyai.auth.AuthConstants
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
