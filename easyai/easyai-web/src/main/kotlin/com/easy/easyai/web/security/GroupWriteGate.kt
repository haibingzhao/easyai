package com.easy.easyai.web.security

import com.easy.easyai.auth.group.GroupClaims
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException

/**
 * Which ownership bucket a config write targets.
 *
 * The console's group-configuration screens send [GROUP]; every other caller (and any legacy client
 * that omits the parameter) is [PERSONAL]. Naming the value `group` rather than `family` keeps it
 * usable for any future shared-ownership bucket the product introduces.
 */
enum class AssetScope { PERSONAL, GROUP }

/**
 * Resolve the `owner_id` a write must persist under, enforcing the group-owner gate at one choke
 * point so no individual controller can forget it. Reads the security context, then delegates to the
 * pure [resolveWriteOwner] overload (unit-tested on its own).
 */
suspend fun resolveWriteOwner(scope: AssetScope?): String =
    resolveWriteOwner(getCurrentUserId(), currentGroupClaims(), scope)

/**
 * The gate decision, isolated from the security context so it can be tested directly:
 *
 * - [AssetScope.PERSONAL] → [userId]; any authenticated actor may write their own assets.
 * - [AssetScope.GROUP] → the group bucket id, and **only** if [claims] says the actor is the group
 *   owner. A member (reads the group's assets, does not own them) gets 403; a login with no active
 *   group gets 400. The returned id is passed straight to the store as the write owner, whose
 *   `filterStrict(owner)` scoping then makes a forged personal write unable to touch group rows.
 */
internal fun resolveWriteOwner(userId: String, claims: GroupClaims, scope: AssetScope?): String {
    if (scope != AssetScope.GROUP) return userId
    val groupUserId = claims.groupUserId
        ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "No active group for a group-scoped write")
    if (!claims.isGroupOwner) {
        throw ResponseStatusException(HttpStatus.FORBIDDEN, "Only the group owner may modify shared group assets")
    }
    return groupUserId
}

/** Parse the `scope` request parameter, tolerating case/whitespace; absent or unknown → PERSONAL. */
fun parseAssetScope(raw: String?): AssetScope =
    if (raw?.trim().equals("group", ignoreCase = true)) AssetScope.GROUP else AssetScope.PERSONAL
