package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.skills.SkillCatalogSyncService
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillIndexer
import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.skills.SkillRefreshService
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards how the skill RAG beans are wired in [EasyAiCoreAutoConfiguration].
 *
 * Two invariants carry the whole backward-compatibility promise:
 * - every service that writes to the index or to the catalog table sits behind
 *   `easyai.skills.rag.enabled` with `matchIfMissing = false`, so a default deployment creates none
 *   of them;
 * - the prompt view is *not* behind that flag, because it also has to filter disabled rows while the
 *   full catalogue is still injected — and it keeps injecting when the flag is on but no store bean
 *   was ever created.
 */
class SkillRagWiringTest {

    private val configuration = EasyAiCoreAutoConfiguration(EasyAiProperties())

    private fun conditionOn(methodName: String): ConditionalOnProperty? =
        EasyAiCoreAutoConfiguration::class.java.declaredMethods
            .first { it.name == methodName }
            .getAnnotation(ConditionalOnProperty::class.java)

    @Nested
    inner class `the flag owns every write path` {

        @Test
        fun `each skill RAG service is gated on the flag with no default`() {
            val gated = listOf(
                "skillIndexer",
                "skillCatalogSyncService",
                "skillCatalogService",
                "skillRefreshService",
                "skillIndexStartupRunner"
            )

            for (methodName in gated) {
                val condition = assertNotNull(
                    conditionOn(methodName),
                    "$methodName must declare a @ConditionalOnProperty"
                )
                assertEquals("easyai.skills.rag", condition.prefix, "wrong namespace on $methodName")
                assertTrue("enabled" in condition.name, "$methodName must key on 'enabled' but on ${condition.name.toList()}")
                assertEquals("true", condition.havingValue, methodName)
                assertFalse(condition.matchIfMissing, "$methodName would activate by default")
            }
        }

        @Test
        fun `the prompt view stays reachable whatever the flag says`() {
            assertNull(
                conditionOn("skillPromptSource"),
                "disabled-row filtering must work with skill RAG off, so the bean cannot be gated"
            )
        }

        @Test
        fun `the defaults keep every new behaviour switched off`() {
            val skills = EasyAiProperties().skills

            assertFalse(skills.rag.enabled, "on-demand discovery must not activate on its own")
            assertTrue(skills.injectIntoSystemPrompt, "today's prompt behaviour stays the default")
        }
    }

    @Nested
    inner class `what the wiring hands to each bean` {

        private val registry = mockk<SkillRegistry>()
        private val catalog = mockk<AsyncSkillCatalogStore>()
        private val store = mockk<SkillStore>()
        private val syncService = SkillCatalogSyncService(catalog)

        private val enabledProperties = EasyAiProperties(
            skills = SkillProperties(rag = SkillRagProperties(enabled = true))
        )

        @Test
        fun `the prompt view keeps injecting whatever the store bean says`() {
            val withoutStore = configuration.skillPromptSource(registry, catalog, null, enabledProperties)
            val withStore = configuration.skillPromptSource(registry, catalog, store, enabledProperties)

            assertTrue(
                withoutStore.fullInjectionActive,
                "nothing can answer skill_search, so the list must stay in the prompt"
            )
            assertTrue(
                withStore.fullInjectionActive,
                "readiness is judged per skill and per request inside skillsForPrompt, never by bean presence"
            )
        }

        @Test
        fun `a disabled row is filtered even while the full list is injected`() = runBlocking {
            val registry = mockk<SkillRegistry>()
            val installDir = Path.of(System.getProperty("user.home"), ".easyai", "skills", "pdf-report")
            every { registry.all() } returns listOf(
                SkillInfo(
                    name = "pdf-report",
                    description = "Builds PDF reports",
                    location = installDir.resolve("SKILL.md"),
                    content = "# pdf-report"
                )
            )
            coEvery { catalog.listDistinctUserIds() } returns listOf(SkillCatalogEntry.DEFAULT_USER_ID)
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns listOf(
                SkillCatalogEntry(
                    id = "row-1",
                    name = "pdf-report",
                    checksum = "a".repeat(64),
                    enabled = false,
                    installPath = installDir.toString()
                )
            )

            val promptSource = configuration.skillPromptSource(registry, catalog, null, EasyAiProperties())

            assertEquals(
                emptyList(),
                promptSource.skillsForPrompt(SkillCatalogEntry.DEFAULT_USER_ID, null, listOf("pdf-report")),
                "a skill the user switched off must not be advertised by the prompt either"
            )
        }

        @Test
        fun `prompt authorization uses the same configured source roots as catalog synchronization`() = runBlocking {
            val properties = EasyAiProperties(skills = SkillProperties(paths = listOf("/configured/skills")))
            val skill = SkillInfo("custom", "Configured skill", Path.of("/configured/skills/custom/SKILL.md"), "body")
            every { registry.all() } returns listOf(skill)
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns listOf(
                SkillCatalogEntry(name = skill.name, checksum = "checksum", installPath = skill.location.parent.toString())
            )
            val prompt = configuration.skillPromptSource(registry, catalog, null, properties)
            assertEquals(listOf("custom"), prompt.effectiveNames(SkillCatalogEntry.DEFAULT_USER_ID, null))
        }

        @Test
        fun `the refresh chain needs the whole wiring before it exists`() {
            val indexer = SkillIndexer(
                skillStore = store,
                catalog = catalog,
                syncService = syncService,
                config = SkillConfig()
            )
            assertNotNull(
                configuration.skillRefreshService(indexer, syncService, SkillConfig(), registry, catalog, store),
                "registry, catalog and store are all present, so the tool and the startup pass have a service to call"
            )
            assertNull(configuration.skillRefreshService(indexer, syncService, SkillConfig(), null, catalog, store))
            assertNull(configuration.skillRefreshService(indexer, syncService, SkillConfig(), registry, null, store))
            assertNull(configuration.skillRefreshService(indexer, syncService, SkillConfig(), registry, catalog, null))
        }

        @Test
        fun `the startup listener exists exactly as long as the refresh chain does`() {
            val refresher = mockk<SkillRefreshService>()

            assertNotNull(configuration.skillIndexStartupRunner(refresher))
            assertNull(configuration.skillIndexStartupRunner(null))
        }
    }
}
