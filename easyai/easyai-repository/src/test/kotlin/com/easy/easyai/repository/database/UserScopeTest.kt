package com.easy.easyai.repository.database

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The N-value overloads back group sharing: a read sees its own, its group's and the system rows,
 * and `matches` is the in-memory equivalent. The single-id forms must keep their exact old meaning.
 */
class UserScopeTest {

    @Test
    fun `matches N-value accepts any owner in the set`() {
        val owners = listOf("alice", "grp_1", "system")
        assertTrue(UserScope.matches("alice", owners))
        assertTrue(UserScope.matches("grp_1", owners))
        assertTrue(UserScope.matches("system", owners))
        assertFalse(UserScope.matches("bob", owners))
        assertFalse(UserScope.matches("grp_2", owners))
    }

    @Test
    fun `single-id matches still folds in system but not other users`() {
        assertTrue(UserScope.matches("alice", "alice"))
        assertTrue(UserScope.matches("system", "alice"))
        assertFalse(UserScope.matches("bob", "alice"))
    }

    @Test
    fun `N-value filter builds an op for a multi-owner set`() {
        // Rendering the SQL needs a transaction (covered by store integration tests); here we only
        // assert the op is constructed for a group-shaped owner set without throwing.
        assertNotNull(UserScope.filter(Tables.AgentTable.userId, listOf("alice", "grp_1", "system")))
    }
}
