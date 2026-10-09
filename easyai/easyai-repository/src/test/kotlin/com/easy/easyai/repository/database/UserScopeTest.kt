package com.easy.easyai.repository.database

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * [UserScope.filterOwners] backs group sharing: a read sees its own, its group's and the system rows.
 * The single-id forms must keep their exact old meaning.
 */
class UserScopeTest {

    @Test
    fun `single-id matches still folds in system but not other users`() {
        assertTrue(UserScope.matches("alice", "alice"))
        assertTrue(UserScope.matches("system", "alice"))
        assertFalse(UserScope.matches("bob", "alice"))
    }

    @Test
    fun `filterOwners builds an op for a multi-owner set`() {
        // Rendering the SQL needs a transaction (covered by store integration tests); here we only
        // assert the op is constructed for a group-shaped owner set without throwing.
        assertNotNull(UserScope.filterOwners(Tables.AgentTable.userId, listOf("alice", "grp_1", "system")))
    }
}
