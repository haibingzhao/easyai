package com.easy.easyai.web.controller

import com.easy.easyai.auth.group.GroupClaims
import com.easy.easyai.core.storage.StorageSettingsService
import com.easy.easyai.core.storage.StorageSource
import com.easy.easyai.rag.RagConfig
import com.easy.easyai.tools.web.IntegrationConfig
import io.mockk.coEvery
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import reactor.util.context.Context
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapabilityControllerTest {

    private fun controller(
        storage: StorageSettingsService? = null,
        knowledgeSharedWithinGroup: Boolean = false,
        databaseSource: String = "file"
    ) = CapabilityController(storage, knowledgeSharedWithinGroup, databaseSource)

    @AfterEach
    fun tearDown() {
        // The RAG / integration STATIC overrides are process-global; never let them leak across tests.
        RagConfig.setStaticOverride(null)
        IntegrationConfig.setStaticOverride(null)
    }

    private fun asAlice(): Context = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>())
    )

    private fun asGroupMember(isOwner: Boolean): Context = ReactiveSecurityContextHolder.withAuthentication(
        UsernamePasswordAuthenticationToken("alice", "", emptyList<GrantedAuthority>()).apply {
            details = GroupClaims(
                owners = listOf("alice", "grp-1"),
                groupId = "g1",
                groupUserId = "grp-1",
                isGroupOwner = isOwner
            )
        }
    )

    @Nested
    inner class `group-less deployment` {

        @Test
        fun `everyone manages their own assets and there is no shared bucket`() {
            val dto = controller().capabilities().contextWrite(asAlice()).block()!!

            assertNull(dto.groupId)
            assertNull(dto.groupUserId)
            assertFalse(dto.isGroupOwner)
            assertEquals("database", dto.setupMode)
            assertTrue(dto.assets.values.all { it.canRead && it.canManage && it.ownerId == null })
        }
    }

    @Nested
    inner class `group login` {

        @Test
        fun `a member reads the group bucket but may not manage it`() {
            val dto = controller().capabilities().contextWrite(asGroupMember(isOwner = false)).block()!!

            assertEquals("g1", dto.groupId)
            assertEquals("grp-1", dto.groupUserId)
            assertFalse(dto.isGroupOwner)
            val model = dto.assets.getValue("model")
            assertTrue(model.canRead)
            assertFalse(model.canManage)
            assertEquals("grp-1", model.ownerId)
        }

        @Test
        fun `the owner may manage the group bucket`() {
            val dto = controller().capabilities().contextWrite(asGroupMember(isOwner = true)).block()!!

            assertTrue(dto.isGroupOwner)
            assertTrue(dto.assets.values.all { it.canManage })
            assertEquals("grp-1", dto.assets.getValue("skill").ownerId)
        }

        @Test
        fun `knowledge stays member-writable when shared within the group`() {
            val dto = controller(knowledgeSharedWithinGroup = true)
                .capabilities().contextWrite(asGroupMember(isOwner = false)).block()!!

            val knowledge = dto.assets.getValue("knowledge")
            // A member may not manage the owner-gated kinds…
            assertFalse(dto.assets.getValue("model").canManage)
            // …but knowledge is shared-writable by design: every member writes the group slice.
            assertTrue(knowledge.canManage)
            assertEquals("grp-1", knowledge.ownerId)
        }

        @Test
        fun `knowledge is personal when not shared within the group`() {
            val dto = controller(knowledgeSharedWithinGroup = false)
                .capabilities().contextWrite(asGroupMember(isOwner = false)).block()!!

            val knowledge = dto.assets.getValue("knowledge")
            assertTrue(knowledge.canManage)
            assertNull(knowledge.ownerId)
        }
    }

    @Nested
    inner class `static storage layer` {

        @Test
        fun `a pinned storage layer is read-only for everyone and flips setup mode`() {
            val storage = mockk<StorageSettingsService>()
            coEvery { storage.effectiveSource(any<Collection<String>>()) } returns StorageSource.STATIC

            val dto = controller(storage).capabilities().contextWrite(asGroupMember(isOwner = true)).block()!!

            assertEquals("static", dto.setupMode)
            val storageCap = dto.assets.getValue("storage")
            assertTrue(storageCap.canRead)
            assertFalse(storageCap.canManage, "nobody manages a deployment-pinned bucket through the UI")
            assertNull(storageCap.ownerId)
            // Other kinds are unaffected by the storage pin.
            assertTrue(dto.assets.getValue("model").canManage)
        }
    }

    @Nested
    inner class `platform-level configs` {

        @Test
        fun `file-driven rag integrations and database are manageable and carry no owner bucket`() {
            val dto = controller().capabilities().contextWrite(asGroupMember(isOwner = false)).block()!!

            for (kind in listOf("rag", "integrations", "database")) {
                val cap = dto.assets.getValue(kind)
                assertTrue(cap.canRead, "$kind is always readable")
                assertTrue(cap.canManage, "$kind is editable while file-driven, even for a member")
                assertNull(cap.ownerId, "$kind is platform-level, never a group bucket")
            }
        }

        @Test
        fun `a spring-pinned database is read-only for everyone including the owner`() {
            val dto = controller(databaseSource = "spring")
                .capabilities().contextWrite(asGroupMember(isOwner = true)).block()!!

            val database = dto.assets.getValue("database")
            assertTrue(database.canRead)
            assertFalse(database.canManage, "a deployment-pinned database cannot be changed through the UI")
        }

        @Test
        fun `pinned rag and integrations are read-only for everyone`() {
            RagConfig.setStaticOverride(RagConfig(baseUrl = "http://rag.internal:9000"))
            IntegrationConfig.setStaticOverride(IntegrationConfig(exaApiKey = "pinned"))

            val dto = controller().capabilities().contextWrite(asGroupMember(isOwner = true)).block()!!

            assertFalse(dto.assets.getValue("rag").canManage)
            assertFalse(dto.assets.getValue("integrations").canManage)
            // A group asset is untouched by the platform pins.
            assertTrue(dto.assets.getValue("model").canManage)
        }
    }
}
