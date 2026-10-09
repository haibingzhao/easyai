package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelProviderConfigStore
import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.knowledge.KnowledgeEntry
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.storage.StorageSettingsStore
import com.easy.easyai.tools.mcp.AsyncMcpServerStore
import com.easy.easyai.tools.mcp.McpServerConfig
import io.mockk.coEvery
import io.mockk.every
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AssetCleanupServiceTest {

    @Test
    fun `refuses to retire the shared system bucket or a blank id`() = runTest {
        val service = AssetCleanupService()
        assertFailsWith<IllegalArgumentException> { service.retireOwner("system") }
        assertFailsWith<IllegalArgumentException> { service.retireOwner("  ") }
    }

    @Test
    fun `cascades across every wired store and tallies what it removed`() = runTest {
        val modelStore = mockk<ModelProviderConfigStore>()
        val model = mockk<ModelProviderConfig>()
        every { model.id } returns "m1"
        every { model.userId } returns "grp-1"
        coEvery { modelStore.getModelConfigs(any(), "grp-1") } returns emptyList()
        coEvery { modelStore.getModelConfigs(ModelType.CHAT, "grp-1") } returns listOf(model)
        coEvery { modelStore.deleteConfig("m1", "grp-1") } returns true

        val mcpStore = mockk<AsyncMcpServerStore>()
        val server = mockk<McpServerConfig>()
        every { server.name } returns "fs"
        every { server.userId } returns "grp-1"
        coEvery { mcpStore.findAll("grp-1") } returns listOf(server)
        coEvery { mcpStore.delete("fs", "grp-1") } returns Unit

        val storageStore = mockk<StorageSettingsStore>()
        coEvery { storageStore.delete("grp-1") } returns true

        val knowledgeStore = mockk<KnowledgeStore>()
        val entry = mockk<KnowledgeEntry>(); every { entry.key } returns "docs/a.md"
        coEvery { knowledgeStore.list("grp-1") } returns listOf(entry)
        coEvery { knowledgeStore.delete("grp-1", "docs/a.md") } returns true

        val skillCatalog = mockk<AsyncSkillCatalogStore>()
        val skill = mockk<SkillCatalogEntry>(); every { skill.id } returns "s1"; every { skill.name } returns "review"
        coEvery { skillCatalog.listByUser("grp-1") } returns listOf(skill)
        coEvery { skillCatalog.delete("s1") } returns true

        val service = AssetCleanupService(
            modelConfigStore = modelStore,
            mcpServerStore = mcpStore,
            storageSettingsStore = storageStore,
            knowledgeStore = knowledgeStore,
            skillCatalogStore = skillCatalog
        )

        val report = service.retireOwner("grp-1")

        assertEquals(1, report.removed["model"])
        assertEquals(1, report.removed["mcp"])
        assertEquals(1, report.removed["storage"])
        assertEquals(1, report.removed["knowledge"])
        assertEquals(1, report.removed["skill"])
        assertEquals(5, report.totalRemoved)
        assertTrue(report.failures.isEmpty())
        coVerify(exactly = 1) { mcpStore.delete("fs", "grp-1") }
    }

    @Test
    fun `a failure in one kind is recorded and does not abort the rest`() = runTest {
        val modelStore = mockk<ModelProviderConfigStore>()
        coEvery { modelStore.getAllConfigs("grp-1") } throws IllegalStateException("db down")

        val storageStore = mockk<StorageSettingsStore>()
        coEvery { storageStore.delete("grp-1") } returns true

        val service = AssetCleanupService(modelConfigStore = modelStore, storageSettingsStore = storageStore)

        val report = service.retireOwner("grp-1")

        assertEquals(1, report.removed["storage"], "the healthy kind still cleans up")
        assertEquals(1, report.failures.size)
        assertTrue(report.failures.single().startsWith("model:"), "got: ${report.failures}")
    }
}
