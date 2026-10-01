package com.easy.easyai.autoconfigure.core

import com.easy.easyai.core.skill.AsyncSkillCatalogStore
import com.easy.easyai.core.skill.SkillCatalogEntry
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.core.skill.SkillSyncState
import com.easy.easyai.core.storage.ObjectStorageResolver
import com.easy.easyai.skills.SkillConfig
import com.easy.easyai.skills.SkillDiscovery
import com.easy.easyai.skills.SkillInfo
import com.easy.easyai.skills.SkillRefreshService
import com.easy.easyai.skills.SkillRegistry
import com.easy.easyai.skills.SkillSyncService
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Guards how the skill beans are wired in [EasyAiCoreAutoConfiguration].
 *
 * The invariants:
 * - everything that touches disk, catalog or object storage sits behind `easyai.skills.enabled` with
 *   `matchIfMissing = true`, so the master switch really switches the whole pipeline off;
 * - the config view and the prompt view are *not* gated — the prompt view also carries the disabled-row
 *   filtering, and tool builders need a config bean to exist or fall back to defaults;
 * - retrieval suppression needs the whole chain (store + catalog + indexed rows), so a `rag on,
 *   persistence off` deployment keeps injecting the list instead of offering an empty `skill_search`.
 */
class SkillWiringTest {

    private val configuration = EasyAiCoreAutoConfiguration(EasyAiProperties())

    /** Same wiring with on-demand discovery switched on, which is what suppression keys off. */
    private val ragConfiguration = EasyAiCoreAutoConfiguration(
        EasyAiProperties(skills = SkillProperties(rag = SkillRagProperties(enabled = true)))
    )

    private fun conditionOn(methodName: String): ConditionalOnProperty? =
        EasyAiCoreAutoConfiguration::class.java.declaredMethods
            .first { it.name == methodName }
            .getAnnotation(ConditionalOnProperty::class.java)

    private fun <T : Any> providerOf(value: T?): ObjectProvider<T> = mockk(relaxed = true) {
        every { getIfAvailable() } returns value
    }

    @Nested
    inner class `what the master switch gates` {

        @Test
        fun `every pipeline bean is gated on the skills switch`() {
            val gated = listOf(
                "skillDiscovery",
                "skillRegistry",
                "skillAccessResolver",
                "agentSkillFactory",
                "skillPackageStore",
                "skillSyncService",
                "skillIndexer",
                "skillCatalogService",
                "skillRefreshService",
                "skillIndexStartupRunner"
            )

            for (methodName in gated) {
                val condition = assertNotNull(
                    conditionOn(methodName),
                    "$methodName must declare a @ConditionalOnProperty"
                )
                assertEquals("easyai.skills", condition.prefix, "wrong namespace on $methodName")
                assertTrue("enabled" in condition.name, "$methodName must key on 'enabled' but on ${condition.name.toList()}")
                assertEquals("true", condition.havingValue, methodName)
                assertTrue(condition.matchIfMissing, "$methodName must exist with no explicit property")
            }
        }

        @Test
        fun `the config view and the prompt view stay reachable whatever the switch says`() {
            assertNull(
                conditionOn("skillConfig"),
                "tool builders resolve the owner root through this bean, so it must not be gated away"
            )
            assertNull(
                conditionOn("skillPromptSource"),
                "disabled-row filtering must work with no registry and no retrieval index"
            )
        }

        @Test
        fun `the defaults keep retrieval off and the pipeline on`() {
            val skills = EasyAiProperties().skills

            assertTrue(skills.enabled, "skills are core behaviour, not an opt-in")
            assertFalse(skills.rag.enabled, "on-demand discovery must not activate on its own")
            assertTrue(skills.injectIntoSystemPrompt, "today's prompt behaviour stays the default")
            assertTrue(skills.rootDir.endsWith("/.easyai/skills"), "owner roots hang off the user home: ${skills.rootDir}")
            assertEquals(20L * 1024 * 1024, skills.packageMaxBytes)
        }

        @Test
        fun `the property view becomes the registry-facing config`() {
            val config = EasyAiCoreAutoConfiguration(
                EasyAiProperties(
                    skills = SkillProperties(
                        enabled = false,
                        rootDir = "/srv/skills",
                        injectIntoSystemPrompt = false,
                        packageMaxBytes = 123L
                    )
                )
            ).skillConfig()

            assertEquals(SkillConfig(false, "/srv/skills", false, 123L), config)
        }
    }

    @Nested
    inner class `what the wiring hands to each collaborator` {

        private val registry = mockk<SkillRegistry>()
        private val catalog = mockk<AsyncSkillCatalogStore>()
        private val store = mockk<SkillStore>()
        private val syncService = mockk<SkillSyncService>()
        private val refreshService = mockk<SkillRefreshService>()
        private val config = SkillConfig()

        private val installDir = Path.of("/home/alice/.easyai/skills/alice", "pdf-report")
        private val checksum = "a".repeat(64)

        private fun ownRow(enabled: Boolean, indexed: Boolean) = SkillCatalogEntry(
            id = "row-1",
            name = "pdf-report",
            checksum = checksum,
            enabled = enabled,
            rootPath = "/home/alice/.easyai/skills/alice",
            installPath = installDir.toString(),
            objectKey = "skills/alice/pdf-report.zip",
            userId = "alice",
            indexedChecksum = if (indexed) checksum else null,
            syncState = if (indexed) SkillSyncState.SYNCED else SkillSyncState.PENDING_INDEX
        )

        private fun stubCatalogView(row: SkillCatalogEntry?) {
            val skill = SkillInfo(
                name = "pdf-report",
                description = "Builds PDF reports",
                location = installDir.resolve("SKILL.md"),
                content = "# pdf-report"
            )
            every { registry.visibleFor("alice") } returns listOf(skill)
            coEvery { catalog.listByUser("alice") } returns listOfNotNull(row)
            coEvery { catalog.listByUser(SkillCatalogEntry.DEFAULT_USER_ID) } returns emptyList()
        }

        private fun promptSource(
            source: EasyAiCoreAutoConfiguration,
            skillStore: SkillStore?,
            refresher: SkillRefreshService? = null
        ) = source.skillPromptSource(registry, catalog, skillStore, config, providerOf(refresher))

        @Test
        fun `the listing is injected while no retrieval store has been wired`() = runBlocking {
            stubCatalogView(ownRow(enabled = true, indexed = true))

            val skills = promptSource(ragConfiguration, null)
                .skillsForPrompt("alice", listOf("pdf-report"), skillSearchAvailable = true)

            assertEquals(listOf("pdf-report"), skills.map { it["name"] })
        }

        @Test
        fun `a row that is not indexed yet keeps being listed even with a store`() = runBlocking {
            stubCatalogView(ownRow(enabled = true, indexed = false))

            val skills = promptSource(ragConfiguration, store)
                .skillsForPrompt("alice", listOf("pdf-report"), skillSearchAvailable = true)

            assertEquals(listOf("pdf-report"), skills.map { it["name"] }, "skill_search could not answer for it yet")
        }

        @Test
        fun `an indexed catalog row lets skill_search carry the discovery`() = runBlocking {
            stubCatalogView(ownRow(enabled = true, indexed = true))

            val skills = promptSource(ragConfiguration, store)
                .skillsForPrompt("alice", listOf("pdf-report"), skillSearchAvailable = true)

            assertEquals(emptyList(), skills)
        }

        @Test
        fun `a disabled row is filtered even while the full list is injected`() = runBlocking {
            stubCatalogView(ownRow(enabled = false, indexed = false))

            val skills = promptSource(configuration, null)
                .skillsForPrompt("alice", listOf("pdf-report"), skillSearchAvailable = false)

            assertEquals(emptyList(), skills, "a skill the user switched off must not be advertised either")
        }

        @Test
        fun `the first model read syncs the requester's owners`() = runBlocking {
            every { registry.visibleFor("alice") } returns emptyList()
            coEvery { catalog.listByUser(any()) } returns emptyList()
            coEvery { refreshService.ensureSynced("alice") } returns Unit

            promptSource(configuration, store, refreshService).effectiveNames("alice")

            coVerify(exactly = 1) { refreshService.ensureSynced("alice") }
        }

        @Test
        fun `the index projection degrades to no remote writes without a store`() {
            assertNotNull(
                configuration.skillIndexer(syncService, null, catalog),
                "restore and push must keep working with retrieval switched off"
            )
        }

        @Test
        fun `the sync service exists without a catalog and rescans from disk`() {
            assertNotNull(
                configuration.skillSyncService(
                    providerOf<AsyncSkillCatalogStore>(null),
                    providerOf(registry),
                    mockk<SkillDiscovery>(),
                    mockk(),
                    config
                ),
                "single-machine deployments still get a registry populated from disk"
            )
        }

        @Test
        fun `packages fall back to a directory under the skill root when no storage is configured`() {
            val packages = configuration.skillPackageStore(
                providerOf<ObjectStorageResolver>(null),
                SkillConfig(rootDir = tempRoot.toString())
            )
            val key = packages.keyFor("alice", "pdf")

            val storage = runBlocking { packages.storageFor("alice") }
            runBlocking { storage.put(key, "zip-bytes".toByteArray(), "application/zip") }

            assertEquals("zip-bytes", String(runBlocking { storage.get(key) }!!.bytes))
            assertTrue(
                Path.of(tempRoot.toString(), ".packages", key).toFile().isFile,
                "the fallback tree lives inside the skill root"
            )
        }

        @Test
        fun `the startup listener exists without a catalog store`() {
            assertNotNull(
                configuration.skillIndexStartupRunner(
                    mockk<SkillRefreshService>(),
                    providerOf<AsyncSkillCatalogStore>(null)
                )
            )
        }

        @TempDir
        lateinit var tempRoot: Path
    }
}
