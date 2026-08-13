package com.easy.easyai.rag

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HTTP-level tests for [EasyRagClient] against a [MockWebServer].
 * Every test enqueues an `/api/auth-status` response first because the client
 * probes auth lazily on the first exchange.
 */
class EasyRagClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: EasyRagClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        val configPath = Files.createTempFile("easyai-rag-test", ".json")
        runBlocking {
            RagConfig.save(
                RagConfig(enabled = true, baseUrl = server.url("/").toString().trimEnd('/'), workspace = "ws-1"),
                configPath
            )
        }
        client = EasyRagClient(configPath)
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    private fun enqueueAuthStatus() {
        server.enqueue(MockResponse().setBody("""{"auth_required": false}"""))
    }

    private fun sampleDocument(): RagDocument = RagDocument(
        category = RagCategory.MEMORY,
        key = "global/feedback/no-println.md",
        content = "Use a logger instead of println.",
        metadata = mapOf("scope" to "global", "type" to "feedback"),
        createTime = 1_768_435_200L,
        options = RagProcessingOptions(skipKg = true)
    )

    @Test
    fun `upsert posts text then triggers synchronous indexing`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-123","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok","doc_id":"doc-123","final_status":"completed","chunks_count":2}"""))

        val result = client.upsert(sampleDocument())

        assertEquals("doc-123", result.docId)
        assertTrue(result.indexed)
        assertEquals(2, result.chunksCount)

        server.takeRequest() // auth-status
        val insertRequest = server.takeRequest()
        assertEquals("/api/documents/text", insertRequest.path)
        val body = insertRequest.body.readUtf8()
        assertTrue(body.contains(""""externalId":"easyai:memory:global/feedback/no-println.md""""), body)
        assertTrue(body.contains(""""filePath":"easyai/memory/global/feedback/no-println.md""""), body)
        assertTrue(body.contains(""""createTime":1768435200"""), body)
        assertTrue(body.contains(""""skipKg":true"""), body)
        assertTrue(body.contains(""""workspace":"ws-1""""), body)
        assertTrue(body.contains(""""scope":"global""""), body)

        val indexRequest = server.takeRequest()
        assertTrue(indexRequest.path!!.startsWith("/api/documents/doc-123/index"), indexRequest.path)
    }

    @Test
    fun `upsert treats index 409 as already indexed`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"already processed"}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
    }

    @Test
    fun `upsert skips indexing when content unchanged`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"content unchanged, skipped"}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.unchanged)
        server.takeRequest() // auth-status
        server.takeRequest() // insert
        assertEquals(0, server.requestCount - 2)
    }

    @Test
    fun `upsert retries once on 409 from pipeline busy`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"pipeline busy"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok","doc_id":"doc-1","final_status":"completed","chunks_count":1}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
    }

    @Test
    fun `delete tolerates missing document with 404`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))

        client.delete("easyai:memory:global/feedback/no-println.md")

        server.takeRequest() // auth-status
        val lookup = server.takeRequest()
        assertTrue(lookup.path!!.startsWith("/api/documents/by-external-id"), lookup.path)
        assertTrue(lookup.path!!.contains("externalId=easyai"), lookup.path)
        assertEquals(0, server.requestCount - 2)
    }

    @Test
    fun `delete resolves docId then issues delete`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"doc_id":"doc-9","external_id":"x","status":"completed","file_path":"p","content":"c","create_time":1,"chunks_count":1}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        client.delete("easyai:memory:global/user/a.md")

        server.takeRequest() // auth-status
        server.takeRequest() // by-external-id
        val deleteRequest = server.takeRequest()
        assertEquals("DELETE", deleteRequest.method)
        assertTrue(deleteRequest.path!!.startsWith("/api/documents/doc-9"), deleteRequest.path)
    }

    @Test
    fun `search passes filters and time range in request body`() = runTest {
        enqueueAuthStatus()
        server.enqueue(
            MockResponse().setBody(
                """{"chunks":[{"reference_id":"r","content":"hello","file_path":"easyai/memory/global/user/a.md","score":0.9,"create_time":1768435200,"metadata":{"scope":"global"}}]}"""
            )
        )

        val chunks = client.search(
            query = "logging rules",
            category = RagCategory.MEMORY,
            filters = mapOf("scope" to "global"),
            topK = 7,
            timeRangeStart = 100L,
            timeRangeEnd = 200L
        )

        assertEquals(1, chunks.size)
        assertEquals("hello", chunks[0].content)
        assertEquals(1768435200L, chunks[0].createTime)
        assertEquals("global", chunks[0].metadata["scope"])

        server.takeRequest() // auth-status
        val searchRequest = server.takeRequest()
        assertEquals("/api/query/data", searchRequest.path)
        val body = searchRequest.body.readUtf8()
        assertTrue(body.contains(""""mode":"naive""""), body)
        assertTrue(body.contains(""""chunkTopK":7"""), body)
        assertTrue(body.contains(""""timeRangeStart":100"""), body)
        assertTrue(body.contains(""""timeRangeEnd":200"""), body)
        assertTrue(body.contains(""""scope":"global""""), body)
        assertTrue(body.contains(""""category":"memory""""), body)
    }

    @Test
    fun `write operations fail when config disabled`() = runTest {
        val disabledPath = Files.createTempFile("easyai-rag-disabled", ".json")
        RagConfig.save(RagConfig(enabled = false), disabledPath)
        val disabledClient = EasyRagClient(disabledPath)

        assertFailsWith<RagException> {
            disabledClient.upsert(sampleDocument())
        }
    }

    @Test
    fun `read degrades to null when config disabled`() = runTest {
        val disabledPath = Files.createTempFile("easyai-rag-disabled-2", ".json")
        RagConfig.save(RagConfig(enabled = false), disabledPath)
        val disabledClient = EasyRagClient(disabledPath)

        assertEquals(null, disabledClient.readByExternalId("easyai:memory:x"))
        assertEquals(emptyList(), disabledClient.search("q", RagCategory.MEMORY))
        assertEquals(false, disabledClient.healthCheck())
    }

    @Test
    fun `list paginates until total reached`() = runTest {
        enqueueAuthStatus()
        server.enqueue(
            MockResponse().setBody(
                """{"documents":[{"id":"doc-1","filePath":"easyai/memory/global/user/a.md","status":"completed","contentSummary":"s","contentLength":10,"createdAt":"2026-01-15T00:00:00Z","updatedAt":"2026-01-15T00:00:00Z","chunksCount":1,"externalId":"easyai:memory:global/user/a.md","hasStructure":false}],"total":1,"page":1,"pageSize":100}"""
            )
        )

        val docs = client.list(RagCategory.MEMORY, "easyai/memory/global/")

        assertEquals(1, docs.size)
        assertEquals("doc-1", docs[0].docId)
        assertEquals("easyai:memory:global/user/a.md", docs[0].externalId)
    }

    // ── bizId passthrough ──────────────────────────────────────────────

    @Test
    fun `upsert passes bizId in insert body and index query`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok","doc_id":"doc-1","final_status":"completed","chunks_count":1}"""))

        client.upsert(sampleDocument(), bizId = "u_alice")

        server.takeRequest() // auth-status
        val insert = server.takeRequest()
        val insertBody = insert.body.readUtf8()
        assertTrue(insertBody.contains(""""bizId":"u_alice""""), insertBody)

        val index = server.takeRequest()
        assertTrue(index.path!!.contains("bizId=u_alice"), index.path)
    }

    @Test
    fun `upsert omits bizId when absent`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok","doc_id":"doc-1","final_status":"completed","chunks_count":1}"""))

        client.upsert(sampleDocument())

        server.takeRequest() // auth-status
        val insert = server.takeRequest()
        assertFalse(insert.body.readUtf8().contains("bizId"))

        val index = server.takeRequest()
        assertFalse(index.path!!.contains("bizId"), index.path)
    }

    @Test
    fun `search passes bizId in request body`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"chunks":[]}"""))

        client.search(query = "q", category = RagCategory.MEMORY, bizId = "u_alice-demo-12345678")

        server.takeRequest() // auth-status
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""bizId":"u_alice-demo-12345678""""), body)
    }

    @Test
    fun `list passes bizId as query parameter`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"documents":[],"total":0,"page":1,"pageSize":100}"""))

        client.list(RagCategory.MEMORY, "easyai/memory/", bizId = "u_alice")

        server.takeRequest() // auth-status
        val listRequest = server.takeRequest()
        assertTrue(listRequest.path!!.contains("bizId=u_alice"), listRequest.path)
    }

    @Test
    fun `delete passes bizId through lookup and delete`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"doc_id":"doc-9","external_id":"x","status":"completed","file_path":"p","content":"c","create_time":1,"chunks_count":1}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        client.delete("easyai:memory:feedback/no-println.md", bizId = "u_alice")

        server.takeRequest() // auth-status
        val lookup = server.takeRequest()
        assertTrue(lookup.path!!.contains("bizId=u_alice"), lookup.path)
        val deleteRequest = server.takeRequest()
        assertTrue(deleteRequest.path!!.contains("bizId=u_alice"), deleteRequest.path)
    }
}
