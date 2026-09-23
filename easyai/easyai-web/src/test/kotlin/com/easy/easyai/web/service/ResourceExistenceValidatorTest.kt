package com.easy.easyai.web.service

import com.easy.easyai.agent.api.model.AgentCreateRequest
import com.easy.easyai.agent.api.model.InlineAgentSpec
import com.easy.easyai.agent.registry.ToolRegistry
import com.easy.easyai.core.agent.AgentEnv
import com.easy.easyai.core.agent.AgentType
import com.easy.easyai.core.agent.AsyncAgentStore
import com.easy.easyai.web.service.validation.ResourceExistenceValidator
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ResourceExistenceValidatorTest {
    private val validator = ResourceExistenceValidator(mockk<ToolRegistry>(relaxed = true), mockk<AsyncAgentStore>(relaxed = true))

    @Nested
    inner class SkillToolConsistency {
        @Test
        fun `missing load skill is an error for every agent environment and type`() = runTest {
            for (env in AgentEnv.entries) {
                for (type in AgentType.entries) {
                    val request = AgentCreateRequest("test", "Test", agentContext = env, agentType = type, skillNames = listOf("review"))
                    val errors = validator.validate(request, "alice").filter { it.message.contains("'load_skill'") }
                    assertEquals(1, errors.size, "$type in $env must reject unusable skills")
                    assertEquals("error", errors.single().severity)
                }
            }
        }

        @Test
        fun `inline subagents and team members are checked independently`() {
            val request = AgentCreateRequest(
                "test", "Test", toolNames = listOf("load_skill"), skillNames = listOf("review"),
                customSubAgents = listOf(InlineAgentSpec("child", skillNames = listOf("review"))),
                customMembers = listOf(InlineAgentSpec("member", skillNames = listOf("review")))
            )
            val errors = ResourceExistenceValidator.validateSkillTools(request)
            assertEquals(setOf("customSubAgents[0].skillNames", "customMembers[0].skillNames"), errors.map { it.field }.toSet())
            assertTrue(errors.all { it.severity == "error" })
        }

        @Test
        fun `empty skills and slash commands do not require load skill`() {
            assertTrue(ResourceExistenceValidator.validateSkillTools(
                AgentCreateRequest("test", "Test", commandNames = listOf("review"))
            ).isEmpty())
            assertTrue(ResourceExistenceValidator.validateSkillTools(listOf("load_skill"), listOf("review")).isEmpty())
            assertTrue(ResourceExistenceValidator.validateSkillTools(emptyList(), emptyList()).isEmpty())
        }
    }
}
