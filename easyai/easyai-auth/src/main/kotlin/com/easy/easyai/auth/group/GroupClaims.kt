package com.easy.easyai.auth.group

/**
 * Group-sharing claims minted into a token at sign-in and read back per request.
 *
 * A "group" is the framework's shared-ownership bucket; the Easy Home product maps a family onto
 * one. The framework never interprets [groupUserId] beyond treating it as an ordinary owner string
 * that occupies the `user_id` column of shared-asset tables — the product owns the group↔member
 * mapping and decides, at sign-in, which group a login acts under.
 *
 * [owners] holds the ADDITIONAL owner ids the product resolved beyond the caller — typically just
 * `[groupUserId]`. The runtime read filter (`SecurityUtils.currentOwners()`) already unions in the
 * caller's own id and `system`, so implementations must NOT add those: doing so only bloats the JWT
 * (the caller's id is already the token subject). An empty list means "no shared bucket", i.e. a
 * personal session. Never add an owner you cannot positively justify.
 */
data class GroupClaims(
    val owners: List<String> = emptyList(),
    val groupId: String? = null,
    val groupUserId: String? = null,
    val isGroupOwner: Boolean = false
)
