package com.easy.easyai.repository.agent

import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentToolConfig
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.TargetType
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `agent.id` is only unique per owner bucket (the PK is `(id, user_id)`), so `agent_tool` carries
 * its own `user_id` — the owner of the agent row the whitelist belongs to. These tests pin the
 * invariants that follow, against a real H2 schema built from [DatabaseMigration] so the column and
 * its index are exercised, not just the Kotlin mapping.
 *
 * The regression at the centre of it: before the whitelist was owner-scoped, a member creating a
 * personal agent whose id collided with their group's shared one silently replaced the group's
 * TOOL / SUBAGENT / SKILL / MCP / COMMAND / MEMBER entries, because the save path deleted by
 * `agent_id` alone.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class R2dbcAgentStoreOwnerScopeTest {

    private lateinit var db: R2dbcDatabase

    @BeforeAll
    fun setupDb() = runTest {
        db = R2dbcDatabase.connect(
            url = "r2dbc:h2:mem:///agent_owner_scope_${UUID.randomUUID()};MODE=MYSQL;DB_CLOSE_DELAY=-1",
            manager = { TransactionManager(it) }
        )
        DatabaseMigration.defaultTables().execute(db)
    }

    private fun store() = R2dbcAgentStore(db)

    private fun uid(prefix: String) = "$prefix-${UUID.randomUUID().toString().take(8)}"

    private fun agent(id: String, agentType: AgentType = AgentType.PRIMARY) =
        AgentDefinition.create(id = id, name = id, agentType = agentType)

    @Nested
    inner class CollidingIdAcrossBuckets {

        @Test
        fun `each bucket keeps its own tool whitelist for the same agent id`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("grp")
            val id = "research"

            s.save(agent(id), group)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("grep", "read"), group)
            // The member now creates a personal agent with the same id. This used to wipe the
            // group's whitelist because the delete matched on agent_id alone.
            s.save(agent(id), member)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("bash"), member)

            assertEquals(listOf("bash"), s.getAgentToolNames(id, member))
            assertEquals(listOf("grep", "read"), s.getAgentToolNames(id, group), "group whitelist must survive")
        }

        @Test
        fun `every target type is isolated per bucket`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("grp")
            val id = "studio"

            s.save(agent(id), group)
            s.saveAgentSkills(id, listOf("pdf"), group)
            s.saveAgentMembers(id, listOf("analyst"), group)
            s.saveAgentCommands(id, listOf("review"), group)
            s.saveAgentMcpConfigs(id, listOf(), group)

            s.save(agent(id), member)
            s.saveAgentSkills(id, listOf("xlsx"), member)
            s.saveAgentCommands(id, listOf("build"), member)

            assertEquals(listOf("xlsx"), s.getAgentSkillNames(id, member))
            assertEquals(listOf("build"), s.getAgentCommandNames(id, member))
            // Untouched in the member's bucket, and still intact in the group's.
            assertTrue(s.getAgentMemberIds(id, member).isEmpty())
            assertEquals(listOf("pdf"), s.getAgentSkillNames(id, group))
            assertEquals(listOf("analyst"), s.getAgentMemberIds(id, group))
            assertEquals(listOf("review"), s.getAgentCommandNames(id, group))
        }

        @Test
        fun `a whitelist read under the wrong owner is empty`() = runTest {
            val s = store()
            val owner = uid("owner")
            val stranger = uid("stranger")
            val id = "private-agent"

            s.save(agent(id), owner)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("write"), owner)

            assertTrue(s.getAgentToolNames(id, stranger).isEmpty(), "no cross-bucket whitelist leakage")
            assertTrue(s.getAgentToolConfigs(id, TargetType.TOOL, stranger).isEmpty())
        }

        @Test
        fun `delete removes only the deleted bucket's whitelist`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("grp")
            val id = "coder"

            s.save(agent(id), group)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("grep"), group)
            s.save(agent(id), member)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("bash"), member)

            s.delete(id, member)

            assertNull(s.findById(id, member), "member's agent row is gone")
            assertNotNull(s.findById(id, group), "group's agent row must survive")
            assertEquals(listOf("grep"), s.getAgentToolNames(id, group))
        }
    }

    @Nested
    inner class DefinitionCarriesItsOwnWhitelist {

        @Test
        fun `findById returns the resolved bucket's tool names`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("grp")
            val id = "planner"

            s.save(agent(id), group)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("grep"), group)
            s.save(agent(id), member)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("bash", "write"), member)

            assertEquals(listOf("bash", "write"), s.findById(id, member)?.toolNames)
            assertEquals(listOf("grep"), s.findById(id, group)?.toolNames)
            assertEquals(group, s.findById(id, group)?.userId, "the owner the whitelist was read under")
        }

        @Test
        fun `findByIds fills each agent's tool names from its own bucket`() = runTest {
            val s = store()
            val caller = uid("caller")
            s.save(agent("own-agent"), caller)
            s.saveAgentTools("own-agent", listOf("bash"), caller)
            s.save(agent("shared-agent"), "system")
            s.saveAgentTools("shared-agent", listOf("read"), "system")

            val loaded = s.findByIds(listOf("own-agent", "shared-agent"), caller)

            assertEquals(listOf("bash"), loaded["own-agent"]?.toolNames)
            assertEquals(listOf("read"), loaded["shared-agent"]?.toolNames)
        }

        @Test
        fun `findAll across owners shadows a colliding id with the higher-priority bucket`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("grp")
            val id = "analyst"

            s.save(agent(id), group)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("grep"), group)
            s.save(agent(id), member)
            s.saveAgentToolConfigs(id, TargetType.TOOL, listOf("bash"), member)

            val visible = s.findAll(listOf(member, group, "system"))

            assertEquals(1, visible.count { it.id == id }, "one agent per id after self-over-group shadowing")
            assertEquals(member, visible.first { it.id == id }.userId)
            assertEquals(listOf("bash"), visible.first { it.id == id }.toolNames)
        }
    }

    @Nested
    inner class InlineEntries {

        @Test
        fun `saving global references preserves inline entries of the same bucket only`() = runTest {
            val s = store()
            val member = uid("member")
            val group = uid("grp")
            val id = "team-leader"

            s.save(agent(id, AgentType.TEAM), group)
            s.saveAgentInlineSpecs(id, TargetType.MEMBER, listOf(inline("group-helper")), group)
            s.saveAgentMembers(id, listOf("analyst"), group)

            s.save(agent(id, AgentType.TEAM), member)
            s.saveAgentInlineSpecs(id, TargetType.MEMBER, listOf(inline("member-helper")), member)
            s.saveAgentMembers(id, listOf("coder"), member)

            val memberEntries = s.getAgentToolConfigs(id, TargetType.MEMBER, member).map { it.targetName }
            assertEquals(listOf("coder", "inline:member-helper").toSet(), memberEntries.toSet())

            val groupEntries = s.getAgentToolConfigs(id, TargetType.MEMBER, group).map { it.targetName }
            assertEquals(listOf("analyst", "inline:group-helper").toSet(), groupEntries.toSet())
        }
    }

    private fun inline(name: String) = AgentToolConfig(
        id = UUID.randomUUID().toString(),
        agentId = "ignored",
        targetType = TargetType.MEMBER,
        targetName = "inline:$name",
        metadata = """{"name":"$name","description":"d","systemPrompt":"p"}"""
    )
}
