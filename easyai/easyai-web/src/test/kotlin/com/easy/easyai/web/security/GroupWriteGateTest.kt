package com.easy.easyai.web.security

import com.easy.easyai.auth.group.GroupClaims
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The gate is the single choke point that stops a member from writing group-owned assets. These are
 * the privilege-escalation cases that must never slip: a member asking for a group write is 403, a
 * group-less login is 400, and personal writes are always the caller's own id.
 */
class GroupWriteGateTest {

    private val owner = GroupClaims(owners = listOf("alice", "grp_1"), groupId = "fam-1", groupUserId = "grp_1", isGroupOwner = true)
    private val member = GroupClaims(owners = listOf("bob", "grp_1"), groupId = "fam-1", groupUserId = "grp_1", isGroupOwner = false)
    private val groupLess = GroupClaims(owners = listOf("carol"))

    @Test
    fun `personal scope always writes under the caller`() {
        assertEquals("alice", resolveWriteOwner("alice", owner, AssetScope.PERSONAL))
        assertEquals("bob", resolveWriteOwner("bob", member, AssetScope.PERSONAL))
        assertEquals("carol", resolveWriteOwner("carol", groupLess, null))
    }

    @Test
    fun `group scope writes under the bucket for the group owner`() {
        assertEquals("grp_1", resolveWriteOwner("alice", owner, AssetScope.GROUP))
    }

    @Test
    fun `a member is forbidden from a group write`() {
        val e = assertFailsWith<ResponseStatusException> { resolveWriteOwner("bob", member, AssetScope.GROUP) }
        assertEquals(HttpStatus.FORBIDDEN, e.statusCode)
    }

    @Test
    fun `a group-less login cannot make a group write`() {
        val e = assertFailsWith<ResponseStatusException> { resolveWriteOwner("carol", groupLess, AssetScope.GROUP) }
        assertEquals(HttpStatus.BAD_REQUEST, e.statusCode)
    }

    @Test
    fun `scope parsing tolerates case and blanks, defaulting to personal`() {
        assertEquals(AssetScope.GROUP, parseAssetScope("group"))
        assertEquals(AssetScope.GROUP, parseAssetScope("  GROUP "))
        assertEquals(AssetScope.PERSONAL, parseAssetScope("personal"))
        assertEquals(AssetScope.PERSONAL, parseAssetScope(null))
        assertEquals(AssetScope.PERSONAL, parseAssetScope("nonsense"))
    }
}
