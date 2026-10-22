package com.easy.easyai.web.controller

import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AgentEnv
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.ChatPreference
import com.easy.easyai.core.agent.ChatPreferenceStore
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.web.server.ResponseStatusException
import reactor.util.context.Context
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [ChatPreferenceController] validation and layering.
 *
 * The persisted store is replaced by an in-memory map — its SQL behaviour is pinned separately in
 * `R2dbcChatPreferenceStoreTest` — so these tests focus on the decisions a bad configuration would
 * otherwise smuggle in: a hidden default, an empty picker, a stale agent id, or a personal edit
 * silently landing in the shared group bucket.
 */
class ChatPreferenceControllerTest {

    private class InMemoryPreferenceStore : ChatPreferenceStore {
        val rows = mutableMapOf<String, ChatPreference>()
        override suspend fun get(userId: String): ChatPreference? = rows[userId]
        override suspend fun save(preference: ChatPreference) {
            rows[preference.userId] = preference
        }
        override suspend fun delete(userId: String): Boolean = rows.remove(userId) != null
    }

    private val preferenceStore = InMemoryPreferenceStore()
    private val agentStore = mockk<AsyncAgentStore>()

    private val code = agent("default-agent")
    private val ask = agent("ask-agent")
    private val subAgent = agent("worker", AgentType.SUBAGENT)
    private val personal = agent("mine", userId = "alice")

    private fun agent(
        id: String,
        type: AgentType = AgentType.PRIMARY,
        env: AgentEnv = AgentEnv.CHAT,
        enabled: Boolean = true,
        userId: String = "system"
    ) = AgentDefinition.create(id = id, name = id, agentType = type, agentContext = env, enabled = enabled)
        .copy(userId = userId)

    private fun controller() = ChatPreferenceController(agentStore, preferenceStore)

    @BeforeEach
    fun stubAgentStore() {
        val all = listOf(code, ask, subAgent, personal)
        coEvery { agentStore.findChatAgents(any<Collection<String>>()) } answers {
            val owners = firstArg<Collection<String>>()
            all.filter {
                it.userId in owners && (it.agentContext == AgentEnv.CHAT || it.agentContext == AgentEnv.BOTH)
            }
        }
        coEvery { agentStore.findById(any(), any<Collection<String>>()) } answers {
            val id = firstArg<String>()
            val owners = secondArg<Collection<String>>()
            all.find { it.id == id && it.userId in owners }
        }
        coEvery { agentStore.findByIds(any(), any<Collection<String>>()) } answers {
            val ids = firstArg<Collection<String>>().toSet()
            val owners = secondArg<Collection<String>>()
            all.filter { it.id in ids && it.userId in owners }.associateBy { it.id }
        }
    }

    private fun asAlice(claims: GroupClaims? = null): Context =
        ReactiveSecurityContextHolder.withAuthentication(
            UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>()).apply {
                details = claims
            }
        )

    private fun asGroupOwner(): Context = asAlice(
        GroupClaims(owners = listOf("grp-1"), groupId = "g1", groupUserId = "grp-1", isGroupOwner = true)
    )

    private fun asGroupMember(): Context = asAlice(
        GroupClaims(owners = listOf("grp-1"), groupId = "g1", groupUserId = "grp-1", isGroupOwner = false)
    )

    private fun badRequest(block: () -> Unit) {
        val ex = assertFailsWith<ResponseStatusException> { block() }
        assertEquals(HttpStatus.BAD_REQUEST, ex.statusCode)
    }

    @Nested
    inner class `effective read` {

        @Test
        fun `no stored row reports the built-in defaults`() {
            val dto = controller().get().contextWrite(asAlice()).block()!!

            assertNull(dto.defaultAgentId)
            assertEquals(emptyList(), dto.hiddenAgentIds)
            assertEquals("default", dto.effectiveSource)
            assertEquals("default", dto.source)
        }

        @Test
        fun `a member without a personal row reads the group configuration`() {
            preferenceStore.rows["grp-1"] = ChatPreference("grp-1", "default-agent", listOf("ask-agent"))

            val dto = controller().get().contextWrite(asGroupMember()).block()!!

            assertEquals("default-agent", dto.defaultAgentId)
            assertEquals(listOf("ask-agent"), dto.hiddenAgentIds)
            assertEquals("group", dto.effectiveSource)
        }

        @Test
        fun `a personal row shadows the group configuration`() {
            preferenceStore.rows["grp-1"] = ChatPreference("grp-1", "default-agent", listOf("ask-agent"))
            preferenceStore.rows["alice"] = ChatPreference("alice", null, listOf("default-agent"))

            val dto = controller().get().contextWrite(asGroupMember()).block()!!

            assertNull(dto.defaultAgentId)
            assertEquals(listOf("default-agent"), dto.hiddenAgentIds)
            assertEquals("user", dto.effectiveSource)
            assertEquals("user", dto.source)
        }

        @Test
        fun `a personal-scoped read returns the personal row even when the group wins`() {
            preferenceStore.rows["grp-1"] = ChatPreference("grp-1", "default-agent", listOf("ask-agent"))
            preferenceStore.rows["alice"] = ChatPreference("alice", "ask-agent", emptyList())

            val dto = controller().get("personal").contextWrite(asGroupMember()).block()!!

            assertEquals("ask-agent", dto.defaultAgentId)
            assertEquals(emptyList(), dto.hiddenAgentIds)
            assertEquals("user", dto.source)
            assertEquals("user", dto.effectiveSource)
        }

        @Test
        fun `a group-scoped read returns the shared row even when a personal row shadows it`() {
            preferenceStore.rows["grp-1"] = ChatPreference("grp-1", "default-agent", listOf("ask-agent"))
            preferenceStore.rows["alice"] = ChatPreference("alice", "ask-agent", emptyList())

            val dto = controller().get("group").contextWrite(asGroupMember()).block()!!

            assertEquals("default-agent", dto.defaultAgentId)
            assertEquals(listOf("ask-agent"), dto.hiddenAgentIds)
            assertEquals("group", dto.source)
            assertEquals("user", dto.effectiveSource)
        }

        @Test
        fun `a group-scoped read without an active group is refused`() {
            badRequest {
                controller().get("group").contextWrite(asAlice()).block()
            }
        }
    }

    @Nested
    inner class `write validation` {

        @Test
        fun `saving a default and a hidden list normalizes into the personal bucket`() {
            val dto = controller()
                .save(SaveChatPreferenceRequest("ask-agent", listOf(" default-agent ", "default-agent")), null)
                .contextWrite(asAlice())
                .block()!!

            assertEquals("ask-agent", dto.defaultAgentId)
            assertEquals(listOf("default-agent"), dto.hiddenAgentIds)
            assertEquals("user", dto.effectiveSource)
            assertEquals(setOf("alice"), preferenceStore.rows.keys)
        }

        @Test
        fun `the default agent cannot be hidden at the same time`() {
            badRequest {
                controller()
                    .save(SaveChatPreferenceRequest("ask-agent", listOf("ask-agent")), null)
                    .contextWrite(asAlice())
                    .block()
            }
            assertTrue(preferenceStore.rows.isEmpty())
        }

        @Test
        fun `an unknown agent id is refused`() {
            badRequest {
                controller()
                    .save(SaveChatPreferenceRequest(null, listOf("ghost")), null)
                    .contextWrite(asAlice())
                    .block()
            }
        }

        @Test
        fun `a sub-agent cannot become the default`() {
            badRequest {
                controller()
                    .save(SaveChatPreferenceRequest("worker", emptyList()), null)
                    .contextWrite(asAlice())
                    .block()
            }
        }

        @Test
        fun `a non-selectable agent cannot be hidden`() {
            badRequest {
                controller()
                    .save(SaveChatPreferenceRequest(null, listOf("worker")), null)
                    .contextWrite(asAlice())
                    .block()
            }
            assertTrue(preferenceStore.rows.isEmpty())
        }

        @Test
        fun `hiding every chat agent is refused`() {
            badRequest {
                controller()
                    .save(SaveChatPreferenceRequest(null, listOf("default-agent", "ask-agent", "mine")), null)
                    .contextWrite(asAlice())
                    .block()
            }
        }

        @Test
        fun `a personal configuration may hide an agent only the caller can see`() {
            val dto = controller()
                .save(SaveChatPreferenceRequest(null, listOf("mine")), null)
                .contextWrite(asAlice())
                .block()!!

            assertEquals(listOf("mine"), dto.hiddenAgentIds)
            assertEquals("user", dto.effectiveSource)
        }

        @Test
        fun `an empty payload deletes the target row and still reports the winning layer`() {
            preferenceStore.rows["grp-1"] = ChatPreference("grp-1", "default-agent", listOf("ask-agent"))
            preferenceStore.rows["alice"] = ChatPreference("alice", "ask-agent", emptyList())

            val dto = controller()
                .save(SaveChatPreferenceRequest(), null)
                .contextWrite(asGroupMember())
                .block()!!

            assertFalse(preferenceStore.rows.containsKey("alice"))
            // The target (personal) row is gone, so the editor sees built-in defaults…
            assertNull(dto.defaultAgentId)
            assertEquals(emptyList(), dto.hiddenAgentIds)
            assertEquals("default", dto.source)
            // …while the caller still gets the group row.
            assertEquals("group", dto.effectiveSource)
        }
    }

    @Nested
    inner class `group scope` {

        @Test
        fun `a group owner writes the shared bucket, not their personal one`() {
            preferenceStore.rows["alice"] = ChatPreference("alice", "ask-agent", emptyList())

            val dto = controller()
                .save(SaveChatPreferenceRequest(null, listOf("default-agent")), "group")
                .contextWrite(asGroupOwner())
                .block()!!

            assertEquals(setOf("grp-1", "alice"), preferenceStore.rows.keys)
            assertEquals(listOf("default-agent"), preferenceStore.rows.getValue("grp-1").hiddenAgentIds)
            // The editor gets the group row it just wrote, not Alice's shadowing personal values…
            assertNull(dto.defaultAgentId)
            assertEquals(listOf("default-agent"), dto.hiddenAgentIds)
            assertEquals("group", dto.source)
            // …while the caller still gets her personal row.
            assertEquals("user", dto.effectiveSource)
        }

        @Test
        fun `a group configuration cannot hide an agent only the owner can see`() {
            badRequest {
                controller()
                    .save(SaveChatPreferenceRequest(null, listOf("mine")), "group")
                    .contextWrite(asGroupOwner())
                    .block()
            }
            assertFalse(preferenceStore.rows.containsKey("grp-1"))
        }

        @Test
        fun `a member cannot write the shared bucket`() {
            val ex = assertFailsWith<ResponseStatusException> {
                controller()
                    .save(SaveChatPreferenceRequest(null, listOf("default-agent")), "group")
                    .contextWrite(asGroupMember())
                    .block()
            }
            assertEquals(HttpStatus.FORBIDDEN, ex.statusCode)
            assertTrue(preferenceStore.rows.isEmpty())
        }
    }
}
