package com.easy.easyai.web.controller

import com.easy.easyai.agent.api.model.AgentConfigsRequest
import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.agent.api.model.AgentToolsRequest
import com.easy.easyai.agent.api.model.InlineAgentSpec
import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.auth.AuthConstants
import com.easy.easyai.core.agent.AgentDefinition
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.core.agent.TargetType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AgentControllerSkillValidationTest {
    private val store = mockk<AsyncAgentStore>(relaxed = true)
    private val controller = AgentController(store, mockk<ToolRegistry>())
    private val invalid = AgentCreateRequest("agent", "Agent", skillNames = listOf("review"))
    private val existing = AgentDefinition.create(id = "agent", name = "Agent").copy(userId = "alice")

    private fun prepareExisting() {
        coEvery { store.findById("agent", any()) } returns existing
    }

    private fun verifyNoWrites() {
        coVerify(exactly = 0) { store.save(any(), any()) }
        coVerify(exactly = 0) { store.update(any(), any()) }
        coVerify(exactly = 0) { store.saveAgentTools(any(), any()) }
        coVerify(exactly = 0) { store.saveAgentToolConfigs(any(), any(), any()) }
        coVerify(exactly = 0) { store.saveAgentInlineSpecs(any(), any(), any()) }
    }

    @Nested
    inner class FullSave {
        @Test
        fun `create rejects missing load tool before persistence`() = runTest {
            coEvery { store.findById(any(), any()) } returns null
            val error = assertFailsWith<ResponseStatusException> { controller.create(invalid).awaitSingle() }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            assertTrue(error.reason.orEmpty().contains("load_skill"))
            verifyNoWrites()
        }

        @Test
        fun `update rejects missing load tool before persistence`() = runTest {
            prepareExisting()
            val error = assertFailsWith<ResponseStatusException> { controller.update("agent", invalid).awaitSingle() }
            assertEquals(HttpStatus.BAD_REQUEST, error.statusCode)
            verifyNoWrites()
        }

        @Test
        fun `create and update reject invalid inline agents even when parent has load tool`() = runTest {
            val inline = InlineAgentSpec("child", skillNames = listOf("review"))
            val request = invalid.copy(toolNames = listOf("load_skill"), customSubAgents = listOf(inline), customMembers = listOf(inline))
            coEvery { store.findById(any(), any()) } returns null
            assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> {
                controller.create(request).awaitSingle()
            }.statusCode)
            prepareExisting()
            assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> {
                controller.update("agent", request).awaitSingle()
            }.statusCode)
            verifyNoWrites()
        }

        @Test
        fun `slash command alone is still savable without load tool`() = runTest {
            coEvery { store.findById(any(), any()) } returns null
            val dto = controller.create(invalid.copy(skillNames = emptyList(), commandNames = listOf("review"))).awaitSingle()
            assertEquals(listOf("review"), dto.commandNames)
            coVerify(exactly = 1) { store.save(any(), AuthConstants.SYSTEM_USER_ID) }
        }
    }

    @Nested
    inner class PartialSave {
        @Test
        fun `tools endpoint cannot remove load tool while skills remain`() = runTest {
            prepareExisting()
            coEvery { store.getAgentSkillNames("agent") } returns listOf("review")
            assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> {
                controller.updateTools("agent", AgentToolsRequest(emptyList())).awaitSingle()
            }.statusCode)
            verifyNoWrites()
        }

        @Test
        fun `configs tool endpoint cannot remove required load tool`() = runTest {
            prepareExisting()
            coEvery { store.getAgentSkillNames("agent") } returns listOf("review")
            assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> {
                controller.saveConfigs("agent", AgentConfigsRequest("TOOL", emptyList())).awaitSingle()
            }.statusCode)
            verifyNoWrites()
        }

        @Test
        fun `configs skill endpoint requires existing load tool`() = runTest {
            prepareExisting()
            coEvery { store.getAgentToolNames("agent") } returns emptyList()
            assertEquals(HttpStatus.BAD_REQUEST, assertFailsWith<ResponseStatusException> {
                controller.saveConfigs("agent", AgentConfigsRequest("SKILL", listOf("review"))).awaitSingle()
            }.statusCode)
            verifyNoWrites()
        }

        @Test
        fun `skills can be cleared and valid tools can be saved`() = runTest {
            prepareExisting()
            controller.saveConfigs("agent", AgentConfigsRequest("SKILL", emptyList())).awaitSingle()
            coVerify(exactly = 1) { store.saveAgentToolConfigs("agent", TargetType.SKILL, emptyList()) }
            coEvery { store.getAgentSkillNames("agent") } returns listOf("review")
            assertEquals(listOf("load_skill"), controller.updateTools("agent", AgentToolsRequest(listOf("load_skill"))).awaitSingle())
        }
    }
}
