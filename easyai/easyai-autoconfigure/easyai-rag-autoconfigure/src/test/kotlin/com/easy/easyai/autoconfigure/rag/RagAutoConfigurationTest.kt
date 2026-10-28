package com.easy.easyai.autoconfigure.rag

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.MemoryConsolidationAgent
import com.easy.easyai.core.memory.MemoryConsolidationSettings
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.skill.SkillStore
import com.easy.easyai.rag.RagArchiveResult
import com.easy.easyai.rag.RagArchiveUpdate
import com.easy.easyai.rag.RagChunk
import com.easy.easyai.rag.RagClient
import com.easy.easyai.rag.RagDocInfo
import com.easy.easyai.rag.RagDocument
import com.easy.easyai.rag.RagDocumentDetail
import com.easy.easyai.rag.RagUpsertResult
import com.easy.easyai.rag.RagWorkspaceConfig
import com.easy.easyai.rag.RagWorkspaceConfigUpdate
import org.junit.jupiter.api.Test
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import java.util.function.Supplier
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Condition-matrix tests for [RagAutoConfiguration]: the RAG beans only exist
 * when `easyai.rag.enabled=true`, the RAG-backed [MemoryStore] only when memory is enabled as
 * well, and the skill index only on its own explicit opt-in.
 */
class RagAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RagAutoConfiguration::class.java))
        // The memory beans resolve their taxonomy through the DomainCatalog bean, which the
        // core auto-configuration owns and this module must not conjure a default for.
        .withBean(
            "domainCatalog",
            DomainCatalog::class.java,
            Supplier { DomainCatalog(emptyList(), DomainCatalog.CODING_DOMAIN) }
        )

    @Test
    fun `no rag beans when rag disabled`() {
        contextRunner
            .withPropertyValues("easyai.rag.enabled=false", "easyai.memory.enabled=true")
            .run { context ->
                assertEquals(0, context.getBeanNamesForType(RagClient::class.java).size)
                assertEquals(0, context.getBeanNamesForType(MemoryStore::class.java).size)
            }
    }

    @Test
    fun `rag beans registered by default when property missing`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeanNamesForType(RagClient::class.java).size)
        }
    }

    @Test
    fun `ragClient registered but no memoryStore when memory disabled`() {
        contextRunner
            .withPropertyValues("easyai.rag.enabled=true", "easyai.memory.enabled=false")
            .run { context ->
                assertEquals(1, context.getBeanNamesForType(RagClient::class.java).size)
                assertEquals(0, context.getBeanNamesForType(MemoryStore::class.java).size)
            }
    }

    @Test
    fun `memoryStore is RAG-backed when both switches enabled`() {
        contextRunner
            .withPropertyValues("easyai.rag.enabled=true", "easyai.memory.enabled=true")
            .run { context ->
                val store = context.getBean(MemoryStore::class.java)
                assertEquals("RagMemoryStore", store::class.java.simpleName)
                assertTrue(context.containsBean("ragClient"))
            }
    }

    @Test
    fun `user-provided RagClient wins over auto-configured one`() {
        val custom: RagClient = FakeRagClient()
        contextRunner
            .withBean("ragClient", RagClient::class.java, Supplier { custom })
            .withPropertyValues("easyai.rag.enabled=true", "easyai.memory.enabled=true")
            .run { context ->
                val beans = context.getBeansOfType(RagClient::class.java)
                assertEquals(1, beans.size)
                assertFalse(beans.values.first()::class.java.name.contains("EasyRagClient"))
            }
    }

    @Test
    fun `no skill store until the skill flag is switched on explicitly`() {
        contextRunner
            .withPropertyValues("easyai.rag.enabled=true", "easyai.memory.enabled=true")
            .run { context ->
                assertEquals(1, context.getBeanNamesForType(MemoryStore::class.java).size)
                assertEquals(
                    0,
                    context.getBeanNamesForType(SkillStore::class.java).size,
                    "skills must keep the pre-RAG behaviour until somebody asks for the index"
                )
            }
    }

    @Test
    fun `the skill store is RAG-backed once both switches are on`() {
        contextRunner
            .withPropertyValues("easyai.rag.enabled=true", "easyai.skills.rag.enabled=true")
            .run { context ->
                assertEquals("RagSkillStore", context.getBean(SkillStore::class.java)::class.java.simpleName)
            }
    }

    @Test
    fun `memory consolidation beans exist by default even without session infrastructure`() {
        contextRunner.run { context ->
            assertEquals(1, context.getBeanNamesForType(MemoryConsolidationAgent::class.java).size)
        }
    }

    @Test
    fun `memory consolidation kill switch removes the agent`() {
        contextRunner
            .withPropertyValues("easyai.memory.consolidation.available=false")
            .run { context ->
                assertEquals(0, context.getBeanNamesForType(MemoryConsolidationAgent::class.java).size)
            }
    }

    @Test
    fun `every consolidation limit is settable, none silently falls back to the core default`() {
        // A core field left out of MemoryConsolidationProperties, or out of the bean's argument list, is
        // not merely un-advertised: auto-configuration rebuilds the core type from these properties, so
        // whatever the operator sets for it is ignored. This test is what makes that omission fail loudly
        // instead of shipping a limit nobody can move.
        contextRunner
            .withPropertyValues(
                "easyai.memory.consolidation.max-grouping-prompt-chars=300000",
                "easyai.memory.consolidation.max-sources-per-group=12",
                "easyai.memory.consolidation.max-group-source-chars=20000",
                "easyai.memory.consolidation.clusters-per-merge-call=5",
                "easyai.memory.consolidation.max-merge-prompt-chars-per-call=60000",
                "easyai.memory.consolidation.max-archives-per-run=400",
                "easyai.memory.consolidation.max-iterations=9",
                "easyai.memory.consolidation.run-timeout-ms=900000",
                "easyai.memory.consolidation.dry-run-timeout-ms=90000",
                "easyai.memory.consolidation.default-threshold=40",
                "easyai.memory.consolidation.daily-cron=0 15 4 * * *",
                "easyai.memory.consolidation.max-tasks-per-tick-per-user=3",
                "easyai.memory.consolidation.max-level=5",
                "easyai.memory.consolidation.zero-gain-backoff-base-days=3",
                "easyai.memory.consolidation.zero-gain-backoff-max-days=21"
            )
            .run { context ->
                val settings = context.getBean(MemoryConsolidationSettings::class.java)
                assertEquals(300_000, settings.maxGroupingPromptChars)
                assertEquals(12, settings.maxSourcesPerGroup)
                assertEquals(20_000, settings.maxGroupSourceChars)
                assertEquals(5, settings.clustersPerMergeCall)
                assertEquals(60_000, settings.maxMergePromptCharsPerCall)
                assertEquals(400, settings.maxArchivesPerRun)
                assertEquals(9, settings.maxIterations)
                assertEquals(900_000L, settings.runTimeoutMs)
                assertEquals(90_000L, settings.dryRunTimeoutMs)
                assertEquals(40, settings.defaultThreshold)
                assertEquals("0 15 4 * * *", settings.dailyCron)
                assertEquals(3, settings.maxTasksPerTickPerUser)
                assertEquals(5, settings.maxLevel)
                assertEquals(3, settings.zeroGainBackoffBaseDays)
                assertEquals(21, settings.zeroGainBackoffMaxDays)
            }
    }

    @Test
    fun `the skill flag alone cannot conjure a store without the RAG client`() {
        contextRunner
            .withPropertyValues("easyai.rag.enabled=false", "easyai.skills.rag.enabled=true")
            .run { context ->
                assertEquals(0, context.getBeanNamesForType(SkillStore::class.java).size)
            }
    }
}

/** Minimal fake for conditional-bean override tests. */
private class FakeRagClient : RagClient {
    override suspend fun isEnabled(): Boolean = true
    override suspend fun healthCheck(): Boolean = true
    override suspend fun upsert(
        doc: RagDocument,
        bizId: String?,
        awaitIndexing: Boolean
    ): RagUpsertResult =
        RagUpsertResult(docId = "fake", indexed = true)
    override suspend fun delete(externalId: String, bizId: String?): Boolean = true
    override suspend fun batchDelete(docIds: List<String>, bizId: String?): Int = 0
    override suspend fun readByExternalId(externalId: String, bizId: String?): RagDocumentDetail? = null
    override suspend fun inspectByExternalId(externalId: String, bizId: String?): RagDocumentDetail? = null
    override suspend fun list(pathPrefix: String, bizId: String?, excludeArchived: Boolean): List<RagDocInfo> =
        emptyList()

    override suspend fun readByDocIds(docIds: List<String>, bizId: String?): List<RagDocumentDetail> =
        emptyList()

    override suspend fun archiveDocIds(
        docIds: List<String>,
        update: RagArchiveUpdate,
        bizId: String?
    ): RagArchiveResult = RagArchiveResult(updated = docIds.size)

    override suspend fun search(
        query: String,
        filters: Map<String, String>,
        topK: Int,
        timeRangeStart: Long?,
        timeRangeEnd: Long?,
        bizId: String?,
        bizIds: List<String>?,
        excludeArchived: Boolean
    ): List<RagChunk> = emptyList()

    override suspend fun getWorkspaceConfig(workspace: String): RagWorkspaceConfig? = null
    override suspend fun upsertWorkspaceConfig(config: RagWorkspaceConfigUpdate): RagWorkspaceConfig =
        RagWorkspaceConfig(workspace = config.workspace)
    override suspend fun deleteWorkspaceConfig(workspace: String) {}
}
