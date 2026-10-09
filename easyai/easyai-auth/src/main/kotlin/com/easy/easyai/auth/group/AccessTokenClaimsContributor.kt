package com.easy.easyai.auth.group

/**
 * Resolves a login's group-sharing claims. Invoked once per token mint — login, refresh and
 * switch-group only, never per request — so an implementation may read the product's group tables
 * directly, with no cache and no invalidation hook to keep coherent.
 *
 * The framework ships [NoopClaimsContributor] as the default: no group, so the runtime read filter
 * stays `{self, system}` — exactly the pre-group behaviour. A product replaces it with a bean that
 * validates the caller's membership of [activeGroupId] and returns that group's bucket id and owner
 * flag; group data stays 100% in the product, the framework stores no group or member table.
 *
 * Contract:
 * - [activeGroupId] is the group the caller asked to act under. It is null for a personal login and
 *   for any refresh token minted before the caller chose a group; an implementation MAY treat null as
 *   "resolve the caller's default group" if the product wants existing sessions to land in one.
 * - Return only the ADDITIONAL owners (typically `[groupUserId]`) — see [GroupClaims]; self and
 *   `system` are unioned in at read time.
 * - Be conservative: on any failure to positively confirm membership, return [GroupClaims] with no
 *   owners rather than guessing — over-granting leaks assets across groups, and the claims are frozen
 *   for the access token's lifetime.
 * - Do NOT throw to signal "not a member of the requested group"; return a group-less result. The
 *   framework turns that into a silent personal session at login, and into an explicit 403 at
 *   `POST /api/auth/switch-group` (which refuses when a requested group resolves to no bucket). A
 *   thrown exception is treated as a backend failure and also degrades to a personal session.
 */
fun interface AccessTokenClaimsContributor {
    suspend fun contributions(userId: String, activeGroupId: String?): GroupClaims
}

/**
 * Default contributor for deployments without group sharing (desktop, CLI, single-tenant servers):
 * no additional owners, so the runtime read filter resolves to `{self, system}`. Returns an empty
 * [GroupClaims] rather than `[userId]` — the caller's own id is already the token subject and is
 * unioned in at read time, so echoing it would only bloat every JWT.
 */
object NoopClaimsContributor : AccessTokenClaimsContributor {
    override suspend fun contributions(userId: String, activeGroupId: String?): GroupClaims = GroupClaims()
}
