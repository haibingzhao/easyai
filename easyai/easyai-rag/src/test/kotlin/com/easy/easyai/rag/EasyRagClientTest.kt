package com.easy.easyai.rag

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
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

    /** Creates a client whose config carries credentials, required for forced re-login paths. */
    private fun clientWithCredentials(): EasyRagClient {
        val authConfigPath = Files.createTempFile("easyai-rag-auth-test", ".json")
        runBlocking {
            RagConfig.save(
                RagConfig(
                    enabled = true,
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    workspace = "ws-1",
                    username = "admin",
                    password = "secret"
                ),
                authConfigPath
            )
        }
        return EasyRagClient(authConfigPath)
    }

    private fun sampleDocument(): RagDocument = RagDocument(
        category = RagCategory.MEMORY,
        key = "global/feedback/no-println.md",
        content = "Use a logger instead of println.",
        metadata = mapOf("scope" to "global", "type" to "feedback"),
        createTime = 1_768_435_200L,
        options = RagProcessingOptions(chunkMethod = "structure_aware", skipKg = false, buildStructure = true)
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
        assertTrue(body.contains(""""chunkMethod":"structure_aware""""), body)
        assertTrue(body.contains(""""skipKg":false"""), body)
        assertTrue(body.contains(""""buildStructure":true"""), body)
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

    // ── auth failure / 401 retry behavior ─────────────────────────────

    @Test
    fun `login 429 then 401 retry re-logs in and carries fresh token`() = runTest {
        val authClient = clientWithCredentials()
        // 1. auth-status probe: auth required
        server.enqueue(MockResponse().setBody("""{"auth_required": true}"""))
        // 2. initial login attempt: rate-limited
        server.enqueue(MockResponse().setResponseCode(429).setBody("""{"error":"too many requests"}"""))
        // 3. first list attempt goes out without a token: rejected
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        // 4. forced re-login triggered by the 401 handler succeeds
        server.enqueue(MockResponse().setBody("""{"token":"fresh-token"}"""))
        // 5. retried list attempt succeeds
        server.enqueue(MockResponse().setBody("""{"documents":[],"total":0,"page":1,"pageSize":100}"""))

        val docs = authClient.list(RagCategory.MEMORY, "easyai/memory/")

        assertEquals(emptyList(), docs)

        server.takeRequest() // auth-status
        server.takeRequest() // login (429)
        val unauthorizedList = server.takeRequest()
        assertNull(unauthorizedList.getHeader("Authorization"), "first list attempt must not carry a token")
        server.takeRequest() // login (forced re-login)
        val retriedList = server.takeRequest()
        assertEquals("Bearer fresh-token", retriedList.getHeader("Authorization"))
    }

    @Test
    fun `concurrent 401 retries trigger only a single re-login`() = runTest {
        val authClient = clientWithCredentials()
        val loginCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/auth-status") ->
                    MockResponse().setBody("""{"auth_required": false}""")
                request.path!!.startsWith("/api/auth/login") -> {
                    loginCount.incrementAndGet()
                    MockResponse().setBody("""{"token":"shared-token"}""")
                }
                request.path!!.startsWith("/api/documents/list") ->
                    if (request.getHeader("Authorization") == "Bearer shared-token") {
                        MockResponse().setBody("""{"documents":[],"total":0,"page":1,"pageSize":100}""")
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val job1 = launch { authClient.list(RagCategory.MEMORY, "easyai/memory/") }
        val job2 = launch { authClient.list(RagCategory.MEMORY, "easyai/memory/") }
        job1.join()
        job2.join()

        assertEquals(1, loginCount.get(), "concurrent 401 retries must deduplicate re-login")
    }

    @Test
    fun `cooldown period still attempts direct login when no token cached`() = runTest {
        val authClient = clientWithCredentials()
        val loginCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/auth-status") ->
                    MockResponse().setBody("""{"auth_required": true}""")
                request.path!!.startsWith("/api/auth/login") ->
                    // Attempts 1 and 2 are rate-limited; attempt 3 succeeds.
                    if (loginCount.incrementAndGet() <= 2) {
                        MockResponse().setResponseCode(429).setBody("""{"error":"too many requests"}""")
                    } else {
                        MockResponse().setBody("""{"token":"late-token"}""")
                    }
                request.path!!.startsWith("/api/documents/list") ->
                    if (request.getHeader("Authorization") == "Bearer late-token") {
                        MockResponse().setBody("""{"documents":[],"total":0,"page":1,"pageSize":100}""")
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }

        // First request: probe login 429 -> list 401 -> forced re-login 429 -> fails.
        assertFailsWith<RagException> {
            authClient.list(RagCategory.MEMORY, "easyai/memory/")
        }
        assertEquals(2, loginCount.get())

        // Second request inside the cooldown window: the fallback direct login
        // succeeds and the list call proceeds with the fresh token.
        val docs = authClient.list(RagCategory.MEMORY, "easyai/memory/")

        assertEquals(emptyList(), docs)
        assertEquals(3, loginCount.get(), "cooldown fallback must perform exactly one more login")
    }

    @Test
    fun `freshly issued token rejected by server triggers exactly one forced re-login`() = runTest {
        val authClient = clientWithCredentials()
        // Reproduces the production timeline: the probe-path login succeeds and
        // caches token-1, yet the very next request carrying token-1 is rejected
        // with 401 (server-side token activation window / invalidation). The 401
        // handler must detect cachedToken == usedToken and force a fresh login
        // instead of reusing the rejected token.
        // 1. auth-status probe: auth required
        server.enqueue(MockResponse().setBody("""{"auth_required": true}"""))
        // 2. probe-path login #1 succeeds -> token-1 cached
        server.enqueue(MockResponse().setBody("""{"token":"token-1"}"""))
        // 3. list attempt carries token-1 but the server rejects it
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":"unauthorized"}"""))
        // 4. 401 handler forces login #2 -> token-2
        server.enqueue(MockResponse().setBody("""{"token":"token-2"}"""))
        // 5. retried list carries token-2 and succeeds
        server.enqueue(MockResponse().setBody("""{"documents":[],"total":0,"page":1,"pageSize":100}"""))

        val docs = authClient.list(RagCategory.MEMORY, "easyai/memory/")

        assertEquals(emptyList(), docs)

        server.takeRequest() // auth-status
        server.takeRequest() // login #1 (probe path)
        val rejectedList = server.takeRequest()
        assertEquals(
            "Bearer token-1",
            rejectedList.getHeader("Authorization"),
            "first list attempt must carry the freshly issued token-1"
        )
        server.takeRequest() // login #2 (forced re-login)
        val retriedList = server.takeRequest()
        assertEquals(
            "Bearer token-2",
            retriedList.getHeader("Authorization"),
            "retry must carry the re-login token-2, not the rejected token-1"
        )
        assertEquals(0, server.requestCount - 5, "no unexpected requests")
    }

    @Test
    fun `concurrent requests during probe share the single login result`() = runTest {
        val authClient = clientWithCredentials()
        val loginCount = AtomicInteger(0)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path!!.startsWith("/api/auth-status") ->
                    MockResponse().setBody("""{"auth_required": true}""")
                request.path!!.startsWith("/api/auth/login") -> {
                    loginCount.incrementAndGet()
                    MockResponse().setBody("""{"token":"shared-token"}""")
                }
                request.path!!.startsWith("/api/documents/list") ->
                    if (request.getHeader("Authorization") == "Bearer shared-token") {
                        MockResponse().setBody("""{"documents":[],"total":0,"page":1,"pageSize":100}""")
                    } else {
                        MockResponse().setResponseCode(401)
                    }
                else -> MockResponse().setResponseCode(404)
            }
        }

        val job1 = launch { authClient.list(RagCategory.MEMORY, "easyai/memory/") }
        val job2 = launch { authClient.list(RagCategory.MEMORY, "easyai/memory/") }
        job1.join()
        job2.join()

        // The first coroutine enters loginMutex, probes auth-status, and logs in.
        // The second coroutine waits on the mutex, then sees authInitialized=true
        // and returns — it must NOT trigger a second login.
        assertEquals(1, loginCount.get(), "concurrent initialization must perform only one login")
    }
}
