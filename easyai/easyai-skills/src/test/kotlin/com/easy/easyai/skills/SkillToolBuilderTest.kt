package com.easy.easyai.skills

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillToolBuilderTest {
    private val f = SkillModelFixture()
    private val agentService = mockk<AgentService>()
    private val catalogProvider = mockk<ObjectProvider<AsyncSkillCatalogStore>>()
    private val configProvider = mockk<ObjectProvider<SkillConfig>>()
    private val storeProvider = mockk<ObjectProvider<SkillStore>>()
    private val context = AgentContext(agentId = "agent", userId = "alice", allowedSkillNames = listOf("review"))

    init {
        every { catalogProvider.getIfAvailable() } returns f.catalog
        every { configProvider.getIfAvailable() } returns f.config
        every { storeProvider.getIfAvailable() } returns null
    }

    @Nested
    inner class Wiring {
        @Test
        fun `load keeps catalog gate when rag is off`() = runTest {
            val skill = f.skill("review")
            f.skills = listOf(skill)
            f.rows = listOf(f.row(skill, enabled = false))
            val tool = assertNotNull(SkillToolBuilder(f.registry, catalogProvider, configProvider, false).build(context, agentService))
            val result = tool.execute(context, "load", args = mapOf("name" to "review"), coroutineScope = this)
            assertTrue(result.isError)
        }

        @Test
        fun `search keeps authorized fallback when index bean is absent`() = runTest {
            val skill = f.skill("review")
            f.skills = listOf(skill)
            f.rows = listOf(f.row(skill))
            val tool = assertNotNull(SkillSearchToolBuilder(
                storeProvider, catalogProvider, 5, f.registry, configProvider, true
            ).build(context, agentService))
            val result = tool.execute(context, "search", args = mapOf("query" to "review"), coroutineScope = this)
            assertFalse(result.isError)
            assertTrue(result.content.filterIsInstance<TextContent>().single().text.contains("[global] review:"))
        }

        @Test
        fun `search is absent for empty whitelist or disabled discovery`() {
            val builder = SkillSearchToolBuilder(storeProvider, catalogProvider, 5, f.registry, configProvider, true)
            assertNull(builder.build(context.copy(allowedSkillNames = emptyList()), agentService))
            assertNull(SkillSearchToolBuilder(storeProvider, catalogProvider, 5, f.registry, configProvider, false).build(context, agentService))
        }

        @Test
        fun `no registry means neither tool is registered`() {
            assertNull(SkillToolBuilder(null, catalogProvider, configProvider, true).build(context, agentService))
            assertNull(SkillSearchToolBuilder(storeProvider, catalogProvider, 5, null, configProvider, true).build(context, agentService))
        }
    }
}
