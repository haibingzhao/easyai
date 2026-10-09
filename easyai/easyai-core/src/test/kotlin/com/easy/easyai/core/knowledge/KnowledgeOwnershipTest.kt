package com.easy.easyai.core.knowledge

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KnowledgeOwnershipTest {

    @Test
    fun `personal by default even when a group bucket is present`() {
        assertEquals("alice", KnowledgeOwnership.ownerId("alice", "grp-1", sharedWithinGroup = false))
    }

    @Test
    fun `group bucket wins when sharing is on`() {
        assertEquals("grp-1", KnowledgeOwnership.ownerId("alice", "grp-1", sharedWithinGroup = true))
    }

    @Test
    fun `falls back to the user when sharing is on but there is no group`() {
        assertEquals("alice", KnowledgeOwnership.ownerId("alice", null, sharedWithinGroup = true))
        assertEquals("alice", KnowledgeOwnership.ownerId("alice", "  ", sharedWithinGroup = true))
    }

    @Test
    fun `a blank user degrades to the system slice`() {
        assertEquals("system", KnowledgeOwnership.ownerId(null, null, sharedWithinGroup = true))
        assertEquals("system", KnowledgeOwnership.ownerId("", null, sharedWithinGroup = false))
    }

    @Test
    fun `group bucket is derived by dropping self and system from the owner set`() {
        assertEquals("grp-1", KnowledgeOwnership.groupBucket("alice", listOf("alice", "grp-1", "system")))
    }

    @Test
    fun `no group bucket when the owner set is just self and system`() {
        assertNull(KnowledgeOwnership.groupBucket("alice", listOf("alice", "system")))
        assertNull(KnowledgeOwnership.groupBucket("alice", null))
    }
}
