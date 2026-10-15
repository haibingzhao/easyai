package com.easy.easyai.web.service

import com.easy.easyai.api.config.ModelCatalogStore
import com.easy.easyai.api.model.ModelCatalogEntry
import com.easy.easyai.api.model.StructuredOutputSupport
import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [ModelCatalogService] paging, mapping and upsert accounting against a fake
 * gateway and an in-memory store — no network, mirroring the console gateway envelope.
 */
class ModelCatalogServiceTest {

    private class FakeGateway(private val pages: Map<Int, String> = emptyMap()) : BailianCatalogGateway {
        val requestedPages = mutableListOf<Int>()
        override suspend fun fetchPage(pageNo: Int, pageSize: Int): String {
            requestedPages += pageNo
            return pages[pageNo] ?: error("unexpected page request: $pageNo")
        }
    }

    private class FakeStore : ModelCatalogStore {
        val rows = mutableMapOf<String, ModelCatalogEntry>()
        override suspend fun getByModelId(modelId: String): ModelCatalogEntry? = rows[modelId]
        override suspend fun listAll(): List<ModelCatalogEntry> = rows.values.sortedBy { it.modelId }
        override suspend fun upsert(entry: ModelCatalogEntry): Boolean = rows.put(entry.modelId, entry) == null
    }

    /** One MAJOR + one SNAPSHOT item in the first group, an item-less group second. */
    private fun pageJson(total: Int, modelSuffix: String = "x") = """
        {
          "code": "200",
          "data": {
            "DataV2": {
              "ret": ["SUCCESS::接口调用成功"],
              "data": {
                "code": "200",
                "data": {
                  "total": $total,
                  "pageNo": 1,
                  "pageSize": 50,
                  "list": [
                    {
                      "model": "group-qwen$modelSuffix",
                      "items": [
                        {
                          "model": "qwen$modelSuffix",
                          "name": "Qwen $modelSuffix",
                          "versionTag": "MAJOR",
                          "shortDescription": "旗舰$modelSuffix",
                          "description": "很长的描述$modelSuffix",
                          "capabilities": ["Reasoning", "VU", "TG"],
                          "features": ["cache", "function-calling", "structured-outputs", "web-search"],
                          "inferenceMetadata": {
                            "request_modality": ["Image", "Text", "Video"],
                            "response_modality": ["Text"]
                          },
                          "contextWindow": 1000000,
                          "maxInputTokens": 991808,
                          "maxOutputTokens": 131072,
                          "prices": [
                            {"type": "input_token", "price": "12", "timeBand": "standard"},
                            {"type": "output_token", "price": "36", "timeBand": "standard"},
                            {"type": "input_token_cache", "price": "1.5", "timeBand": "standard"},
                            {"type": "input_token_batch", "price": "6", "timeBand": "standard"}
                          ]
                        },
                        {
                          "model": "qwen${modelSuffix}-snapshot",
                          "name": "Qwen $modelSuffix snapshot",
                          "versionTag": "SNAPSHOT",
                          "inferenceMetadata": {"request_modality": ["Text"]},
                          "features": []
                        }
                      ]
                    },
                    {
                      "model": "group-empty",
                      "items": []
                    }
                  ]
                }
              }
            }
          }
        }
    """.trimIndent()

    @Nested
    inner class `page parsing` {

        @Test
        fun `maps the MAJOR item into a full catalog entry`() {
            val service = ModelCatalogService(FakeStore(), FakeGateway())
            val page = service.parsePage(pageJson(total = 2))

            assertEquals(2, page.total)
            assertEquals(1, page.entries.size, "item-less groups are skipped and only the MAJOR item maps")
            val entry = page.entries.first()
            assertEquals("qwenx", entry.modelId)
            assertEquals("Qwen x", entry.displayName)
            assertEquals("bailian", entry.vendor)
            assertEquals("bailian-refresh", entry.source)
            assertEquals("旗舰x", entry.description, "shortDescription wins over description")
            assertEquals(true, entry.capabilities?.vision)
            assertEquals(true, entry.capabilities?.supportsToolCalling)
            assertEquals(StructuredOutputSupport.JSON_SCHEMA, entry.capabilities?.structuredOutput)
            assertEquals(true, entry.capabilities?.thinking)
            assertEquals(131072, entry.defaultOptions?.maxTokens)
            assertEquals(1_000_000, entry.defaultOptions?.maxContextTokens)
            assertEquals("CNY", entry.pricing?.currency)
            assertEquals(12.0, entry.pricing?.inputPerMTok)
            assertEquals(36.0, entry.pricing?.outputPerMTok)
            assertEquals(1.5, entry.pricing?.cachedInputPerMTok)
            assertTrue(entry.rawPayload!!.contains("991808"), "raw keeps unmapped fields like maxInputTokens")
        }

        @Test
        fun `absent flags map to the conservative side`() {
            val service = ModelCatalogService(FakeStore(), FakeGateway())
            val item = """
                {"model":"text-only","name":"Text Only","inferenceMetadata":{"request_modality":["Text"]},
                 "capabilities":["TG"],"features":[]}
            """.trimIndent()
            val entry = service.mapItem(SharedObjectMapper.instance.readTree(item))

            assertTrue(entry != null)
            assertEquals(false, entry!!.capabilities?.vision)
            assertEquals(false, entry.capabilities?.supportsToolCalling)
            assertEquals(StructuredOutputSupport.NONE, entry.capabilities?.structuredOutput)
            assertEquals(false, entry.capabilities?.thinking)
            assertNull(entry.defaultOptions?.maxTokens)
            assertNull(entry.pricing)
        }

        @Test
        fun `an item without a model id is dropped`() {
            val service = ModelCatalogService(FakeStore(), FakeGateway())
            assertNull(service.mapItem(SharedObjectMapper.instance.readTree("""{"name":"anonymous"}""")))
        }

        @Test
        fun `a non-envelope response fails loudly`() {
            val service = ModelCatalogService(FakeStore(), FakeGateway())
            assertFailsWith<IllegalStateException> { service.parsePage("""{"code":"401"}""") }
        }

        @Test
        fun `an upstream business error surfaces its message`() {
            val service = ModelCatalogService(FakeStore(), FakeGateway())
            val error = assertFailsWith<IllegalStateException> {
                service.parsePage(
                    """{"code":"200","data":{"DataV2":{"ret":["SUCCESS::接口调用成功"],"data":
                       {"codeEnum":"INVALID_PARAMETER","code":"10110400","success":false,
                        "message":"请求参数不合法: model"}}}}"""
                )
            }
            assertTrue(error.message!!.contains("请求参数不合法"), "the HTTP-200 business error must ride up: ${error.message}")
        }
    }

    @Nested
    inner class `refresh` {

        @Test
        fun `pages until total is covered and counts inserts`() = runTest {
            val gateway = FakeGateway(
                mapOf(
                    1 to pageJson(total = 51, modelSuffix = "1"),
                    2 to pageJson(total = 51, modelSuffix = "2")
                )
            )
            val store = FakeStore()
            val service = ModelCatalogService(store, gateway)

            val result = service.refreshFromBailian()

            assertEquals(listOf(1, 2), gateway.requestedPages)
            assertEquals(CatalogRefreshResult(inserted = 2, updated = 0), result)
            assertEquals(listOf("qwen1", "qwen2"), store.listAll().map { it.modelId })
        }

        @Test
        fun `a second refresh updates in place`() = runTest {
            val gateway = FakeGateway(mapOf(1 to pageJson(total = 2)))
            val store = FakeStore()
            val service = ModelCatalogService(store, gateway)

            service.refreshFromBailian()
            val second = service.refreshFromBailian()

            assertEquals(CatalogRefreshResult(inserted = 0, updated = 1), second)
        }

        @Test
        fun `an empty first page stops without touching the store`() = runTest {
            val gateway = FakeGateway(mapOf(1 to """{"data":{"DataV2":{"data":{"data":{"total":0,"list":[]}}}}}"""))
            val store = FakeStore()

            val result = ModelCatalogService(store, gateway).refreshFromBailian()

            assertEquals(CatalogRefreshResult(0, 0), result)
            assertTrue(store.rows.isEmpty())
        }

        @Test
        fun `missing store fails loudly`() = runTest {
            val service = ModelCatalogService(catalogStore = null, gateway = FakeGateway())
            assertFailsWith<IllegalStateException> { service.refreshFromBailian() }
        }
    }

    @Nested
    inner class `reads` {

        @Test
        fun `lookup and list fall back to empty without a store`() = runTest {
            val service = ModelCatalogService(catalogStore = null, gateway = FakeGateway())
            assertNull(service.lookup("anything"))
            assertEquals(emptyList(), service.list())
        }
    }
}
