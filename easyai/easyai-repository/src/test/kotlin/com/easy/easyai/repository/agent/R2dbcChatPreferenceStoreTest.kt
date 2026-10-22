package com.easy.easyai.repository.agent

import com.easy.easyai.core.agent.ChatPreference
import com.easy.easyai.repository.database.DatabaseMigration
import kotlinx.coroutines.test.runTest
import org.jetbrains.exposed.v1.r2dbc.R2dbcDatabase
import org.jetbrains.exposed.v1.r2dbc.transactions.TransactionManager
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the `chat_preference` contract against a real H2 schema built from [DatabaseMigration], so the
 * JSON column and the owner-keyed upsert are exercised rather than just the Kotlin mapping.
 *
 * The read contract matters most: the effective preference must follow the caller's owner order
 * (self → group), which is how a member inherits the group owner's Chat defaults while retaining the
 * ability to override them personally.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcChatPreferenceStoreTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///chat_preference_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store() = R2dbcChatPreferenceStore(db)

    private fun uid(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    @Nested
    inner class RoundTrip {

        @Test
        fun `a saved preference carries its default agent and hidden ids back`() = runTest {
            val s = store()
            val owner = uid("owner")

            s.save(ChatPreference(owner, defaultAgentId = "ask-agent", hiddenAgentIds = listOf("default-agent", "planner")))

            val loaded = s.get(owner)
            assertEquals("ask-agent", loaded?.defaultAgentId)
            assertEquals(listOf("default-agent", "planner"), loaded?.hiddenAgentIds)
        }

        @Test
        fun `saving the same owner replaces the previous row`() = runTest {
            val s = store()
            val owner = uid("owner")

            s.save(ChatPreference(owner, defaultAgentId = "default-agent", hiddenAgentIds = listOf("ask-agent")))
            s.save(ChatPreference(owner, defaultAgentId = null, hiddenAgentIds = emptyList()))

            val loaded = s.get(owner)
            assertNull(loaded?.defaultAgentId)
            assertEquals(emptyList(), loaded?.hiddenAgentIds)
        }

        @Test
        fun `deleting the row returns the owner to built-in defaults`() = runTest {
            val s = store()
            val owner = uid("owner")
            s.save(ChatPreference(owner, defaultAgentId = "ask-agent", hiddenAgentIds = listOf("default-agent")))

            assertTrue(s.delete(owner))
            assertNull(s.get(owner))
        }
    }

    @Nested
    inner class OwnerResolution {

        @Test
        fun `the personal row shadows the group row`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("group")

            s.save(ChatPreference(member, defaultAgentId = "ask-agent", hiddenAgentIds = listOf("default-agent")))
            s.save(ChatPreference(group, defaultAgentId = "default-agent", hiddenAgentIds = emptyList()))

            val effective = s.getEffective(listOf(member, group))
            assertEquals(member, effective?.userId)
            assertEquals("ask-agent", effective?.defaultAgentId)
        }

        @Test
        fun `a member without a personal row inherits the group row`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("group")
            s.save(ChatPreference(group, defaultAgentId = "default-agent", hiddenAgentIds = listOf("ask-agent")))

            val effective = s.getEffective(listOf(member, group))
            assertEquals(group, effective?.userId)
            assertEquals(listOf("ask-agent"), effective?.hiddenAgentIds)
        }

        @Test
        fun `no row in any owner bucket yields null`() = runTest {
            val s = store()
            assertNull(s.getEffective(listOf(uid("member"), uid("group"))))
        }
    }
}
