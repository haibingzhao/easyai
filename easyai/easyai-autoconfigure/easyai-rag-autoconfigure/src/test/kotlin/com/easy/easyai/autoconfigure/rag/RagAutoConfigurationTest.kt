package com.easy.easyai.autoconfigure.rag

import com.easy.easyai.core.memory.MemoryStore
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
 * when `easyai.rag.enabled=true`, and the RAG-backed [MemoryStore] only when
 * memory is enabled as well.
 */
class RagAutoConfigurationTest {

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(RagAutoConfiguration::class.java))

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
}

/** Minimal fake for conditional-bean override tests. */
private class FakeRagClient : RagClient {
    override suspend fun isEnabled(): Boolean = true
    override suspend fun healthCheck(): Boolean = true
    override suspend fun upsert(doc: RagDocument, bizId: String?): RagUpsertResult =
        RagUpsertResult(docId = "fake", indexed = true)
    override suspend fun delete(externalId: String, bizId: String?) {}
    override suspend fun batchDelete(docIds: List<String>, bizId: String?): Int = 0
    override suspend fun readByExternalId(externalId: String, bizId: String?): RagDocumentDetail? = null
    override suspend fun list(pathPrefix: String, bizId: String?): List<RagDocInfo> = emptyList()
    override suspend fun search(
        query: String,
        filters: Map<String, String>,
        topK: Int,
        timeRangeStart: Long?,
        timeRangeEnd: Long?,
        bizId: String?
    ): List<RagChunk> = emptyList()

    override suspend fun getWorkspaceConfig(workspace: String): RagWorkspaceConfig? = null
    override suspend fun upsertWorkspaceConfig(config: RagWorkspaceConfigUpdate): RagWorkspaceConfig =
        RagWorkspaceConfig(workspace = config.workspace)
    override suspend fun deleteWorkspaceConfig(workspace: String) {}
}
