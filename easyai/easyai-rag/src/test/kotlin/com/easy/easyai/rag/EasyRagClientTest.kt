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
        key = "global/feedback/no-println.md",
        content = "Use a logger instead of println.",
        metadata = mapOf("scope" to "global", "type" to "feedback"),
        createTime = 1_768_435_200L,
        options = RagProcessingOptions(chunkMethod = "structure_aware", skipKg = false, buildStructure = true)
    )

    @Test
    fun `upsert posts text then polls status until processed`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-123","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-123","chunks_count":2}"""))
    
        val result = client.upsert(sampleDocument())
    
        assertEquals("doc-123", result.docId)
        assertTrue(result.indexed)
        assertEquals(2, result.chunksCount)
    
        server.takeRequest() // auth-status
        val insertRequest = server.takeRequest()
        assertEquals("/api/documents/text", insertRequest.path)
        val body = insertRequest.body.readUtf8()
        assertTrue(body.contains(""""externalId":"easyai:global/feedback/no-println.md""""), body)
        assertTrue(body.contains(""""filePath":"easyai/global/feedback/no-println.md""""), body)
        assertTrue(body.contains(""""createTime":1768435200"""), body)
        assertTrue(body.contains(""""chunkMethod":"structure_aware""""), body)
        assertTrue(body.contains(""""skipKg":false"""), body)
        assertTrue(body.contains(""""buildStructure":true"""), body)
        assertTrue(body.contains(""""workspace":"ws-1""""), body)
        assertTrue(body.contains(""""scope":"global""""), body)
    
        val indexRequest = server.takeRequest()
        assertTrue(indexRequest.path!!.startsWith("/api/documents/doc-123/index"), indexRequest.path)
    
        val statusRequest = server.takeRequest()
        assertTrue(statusRequest.path!!.startsWith("/api/documents/status"), statusRequest.path)
        assertTrue(statusRequest.path!!.contains("docId=doc-123"), statusRequest.path)
    }

    @Test
    fun `upsert falls through to polling when index returns 409`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"already processed"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-1","chunks_count":1}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)

        server.takeRequest() // auth-status
        server.takeRequest() // insert
        server.takeRequest() // index (409)
        val statusRequest = server.takeRequest()
        assertTrue(statusRequest.path!!.startsWith("/api/documents/status"), statusRequest.path)
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
    fun `upsert polls until terminal after initial pending status`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setResponseCode(409).setBody("""{"error":"pipeline busy"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-1","chunks_count":1}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
        assertEquals(1, result.chunksCount)

        server.takeRequest() // auth-status
        server.takeRequest() // insert
        server.takeRequest() // index (409)
        server.takeRequest() // status (pending)
        val finalStatus = server.takeRequest()
        assertTrue(finalStatus.path!!.startsWith("/api/documents/status"), finalStatus.path)
    }

    @Test
    fun `delete tolerates missing document with 404`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))

        client.delete("easyai:global/feedback/no-println.md")

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

        client.delete("easyai:global/user/a.md")

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
                """{"chunks":[{"reference_id":"r","content":"hello","file_path":"easyai/global/user/a.md","score":0.9,"create_time":1768435200,"metadata":{"scope":"global"}}]}"""
            )
        )

        val chunks = client.search(
            query = "logging rules",
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

        assertEquals(null, disabledClient.readByExternalId("easyai:x"))
        assertEquals(emptyList(), disabledClient.search("q"))
        assertEquals(false, disabledClient.healthCheck())
    }

    @Test
    fun `list paginates until total reached`() = runTest {
        enqueueAuthStatus()
        server.enqueue(
            MockResponse().setBody(
                """{"documents":[{"id":"doc-1","filePath":"easyai/global/user/a.md","status":"completed","contentSummary":"s","contentLength":10,"createdAt":"2026-01-15T00:00:00Z","updatedAt":"2026-01-15T00:00:00Z","chunksCount":1,"externalId":"easyai:global/user/a.md","hasStructure":false}],"total":1,"page":1,"pageSize":100}"""
            )
        )

        val docs = client.list("easyai/global/")

        assertEquals(1, docs.size)
        assertEquals("doc-1", docs[0].docId)
        assertEquals("easyai:global/user/a.md", docs[0].externalId)
    }

    // ── bizId passthrough ──────────────────────────────────────────────

    @Test
    fun `upsert passes bizId in insert body and status poll query`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-1","chunks_count":1}"""))

        client.upsert(sampleDocument(), bizId = "u_alice")

        server.takeRequest() // auth-status
        val insert = server.takeRequest()
        val insertBody = insert.body.readUtf8()
        assertTrue(insertBody.contains(""""bizId":"u_alice""""), insertBody)

        server.takeRequest() // index
        val status = server.takeRequest()
        assertTrue(status.path!!.contains("bizId=u_alice"), status.path)
    }

    @Test
    fun `upsert omits bizId from status poll when absent`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-1","chunks_count":1}"""))

        client.upsert(sampleDocument())

        server.takeRequest() // auth-status
        val insert = server.takeRequest()
        assertFalse(insert.body.readUtf8().contains("bizId"))

        server.takeRequest() // index
        val status = server.takeRequest()
        assertFalse(status.path!!.contains("bizId"), status.path)
    }

    @Test
    fun `search passes bizId in request body`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"chunks":[]}"""))

        client.search(query = "q", bizId = "u_alice-demo-12345678_m")

        server.takeRequest() // auth-status
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains(""""bizId":"u_alice-demo-12345678_m""""), body)
    }

    @Test
    fun `list passes bizId as query parameter`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"documents":[],"total":0,"page":1,"pageSize":100}"""))

        client.list("easyai/", bizId = "u_alice")

        server.takeRequest() // auth-status
        val listRequest = server.takeRequest()
        assertTrue(listRequest.path!!.contains("bizId=u_alice"), listRequest.path)
    }

    @Test
    fun `delete passes bizId through lookup and delete`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"doc_id":"doc-9","external_id":"x","status":"completed","file_path":"p","content":"c","create_time":1,"chunks_count":1}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        client.delete("easyai:feedback/no-println.md", bizId = "u_alice")

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

        val docs = authClient.list("easyai/")

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

        val job1 = launch { authClient.list("easyai/") }
        val job2 = launch { authClient.list("easyai/") }
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
            authClient.list("easyai/")
        }
        assertEquals(2, loginCount.get())

        // Second request inside the cooldown window: the fallback direct login
        // succeeds and the list call proceeds with the fresh token.
        val docs = authClient.list("easyai/")

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

        val docs = authClient.list("easyai/")

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

        val job1 = launch { authClient.list("easyai/") }
        val job2 = launch { authClient.list("easyai/") }
        job1.join()
        job2.join()

        // The first coroutine enters loginMutex, probes auth-status, and logs in.
        // The second coroutine waits on the mutex, then sees authInitialized=true
        // and returns — it must NOT trigger a second login.
        assertEquals(1, loginCount.get(), "concurrent initialization must perform only one login")
    }

    // ── Async index polling ──────────────────────────────────────────

    @Test
    fun `pollUntilTerminal returns response on processed status`() = runTest {
        val pollConfigPath = Files.createTempFile("easyai-rag-poll-test", ".json")
        runBlocking {
            RagConfig.save(
                RagConfig(
                    enabled = true,
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    workspace = "ws-1",
                    indexPollIntervalMs = 10,
                    indexPollMaxMs = 5_000
                ),
                pollConfigPath
            )
        }
        val pollClient = EasyRagClient(pollConfigPath)
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-p1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-p1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-p1","chunks_count":3}"""))

        val result = pollClient.upsert(sampleDocument())

        assertTrue(result.indexed)
        assertEquals(3, result.chunksCount)
    }

    @Test
    fun `upsert returns indexed=false when polling times out`() = runBlocking {
        val timeoutConfigPath = Files.createTempFile("easyai-rag-timeout-test", ".json")
        runBlocking {
            RagConfig.save(
                RagConfig(
                    enabled = true,
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    workspace = "ws-1",
                    indexPollIntervalMs = 10,
                    indexPollMaxMs = 50
                ),
                timeoutConfigPath
            )
        }
        val timeoutClient = EasyRagClient(timeoutConfigPath)
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-t1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        // All status polls return pending — should timeout
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-t1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-t1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-t1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-t1"}"""))

        val result = timeoutClient.upsert(sampleDocument())

        assertEquals("doc-t1", result.docId)
        assertFalse(result.indexed, "upsert should return indexed=false on poll timeout")
        assertNull(result.chunksCount)
    }

    @Test
    fun `upsert throws when polling returns failed status`() = runTest {
        val failConfigPath = Files.createTempFile("easyai-rag-fail-test", ".json")
        runBlocking {
            RagConfig.save(
                RagConfig(
                    enabled = true,
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    workspace = "ws-1",
                    indexPollIntervalMs = 10,
                    indexPollMaxMs = 5_000
                ),
                failConfigPath
            )
        }
        val failClient = EasyRagClient(failConfigPath)
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-f1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"failed","doc_id":"doc-f1","error":"embedding model unavailable"}"""))

        val ex = assertFailsWith<RagException> {
            failClient.upsert(sampleDocument())
        }
        assertTrue(ex.message!!.contains("indexing failed"), ex.message)
        assertTrue(ex.message!!.contains("embedding model unavailable"), ex.message)
    }

    @Test
    fun `readDocStatusByDocId returns parsed status map`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-s1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-s1","chunks_count":5,"file_path":"p"}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
        assertEquals(5, result.chunksCount)
    }

    @Test
    fun `readDocStatusByDocId returns null on 404`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-nf","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))

        val result = client.upsert(sampleDocument())

        assertFalse(result.indexed)
    }

    @Test
    fun `upsert skips polling when index returns terminal status`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-sync","message":"inserted"}"""))
        // Index endpoint returns a terminal status synchronously — polling should be skipped
        server.enqueue(MockResponse().setBody("""{"status":"processed","chunks_count":4}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
        assertEquals(4, result.chunksCount)

        server.takeRequest() // auth-status
        server.takeRequest() // insert
        server.takeRequest() // index (returns terminal status)
        // No status poll should be issued — verify no more requests
        assertEquals(3, server.requestCount, "no status poll expected when index returns terminal status")
    }

    @Test
    fun `upsert throws when index synchronously returns failed status`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-sf","message":"inserted"}"""))
        // Index endpoint returns failed synchronously — must throw, consistent with the poll path
        server.enqueue(MockResponse().setBody("""{"status":"failed","error":"sync indexing exploded"}"""))

        val ex = assertFailsWith<RagException> {
            client.upsert(sampleDocument())
        }
        assertTrue(ex.message!!.contains("indexing failed"), ex.message)
        assertTrue(ex.message!!.contains("sync indexing exploded"), ex.message)

        server.takeRequest() // auth-status
        server.takeRequest() // insert
        server.takeRequest() // index (returns failed)
        // No status poll should be issued on sync failed
        assertEquals(3, server.requestCount, "no status poll expected when index returns failed")
    }

    @Test
    fun `upsert treats legacy final_status completed as synchronous terminal`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-legacy","message":"inserted"}"""))
        // Legacy synchronous index response format: final_status + completed
        server.enqueue(MockResponse().setBody("""{"status":"ok","doc_id":"doc-legacy","final_status":"completed","chunks_count":2}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
        assertEquals(2, result.chunksCount)

        server.takeRequest() // auth-status
        server.takeRequest() // insert
        server.takeRequest() // index (legacy terminal response)
        // Legacy terminal response must skip polling
        assertEquals(3, server.requestCount, "no status poll expected for legacy terminal response")
    }

    @Test
    fun `upsert treats legacy finalStatus camelCase as synchronous terminal`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-legacy2","message":"inserted"}"""))
        // Legacy synchronous index response format with camelCase field name
        server.enqueue(MockResponse().setBody("""{"status":"ok","doc_id":"doc-legacy2","finalStatus":"completed","chunks_count":3}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
        assertEquals(3, result.chunksCount)
        assertEquals(3, server.requestCount, "no status poll expected for legacy terminal response")
    }

    @Test
    fun `upsert throws with errorMsg field when polling returns failed status`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-em","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        // Failed status carrying the legacy errorMsg field instead of error
        server.enqueue(MockResponse().setBody("""{"status":"failed","doc_id":"doc-em","errorMsg":"chunk pipeline rejected"}"""))

        val ex = assertFailsWith<RagException> {
            client.upsert(sampleDocument())
        }
        assertTrue(ex.message!!.contains("indexing failed"), ex.message)
        assertTrue(ex.message!!.contains("chunk pipeline rejected"), ex.message)
    }

    @Test
    fun `upsert polls when index returns non-terminal status`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-sync","message":"inserted"}"""))
        // Index endpoint returns non-terminal status — code proceeds to poll
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        // Status poll returns processed immediately
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-sync","chunks_count":4}"""))

        val result = client.upsert(sampleDocument())

        assertTrue(result.indexed)
        assertEquals(4, result.chunksCount)

        server.takeRequest() // auth-status
        server.takeRequest() // insert
        server.takeRequest() // index (non-terminal)
        val statusRequest = server.takeRequest()
        assertTrue(statusRequest.path!!.startsWith("/api/documents/status"), statusRequest.path)
    }

    @Test
    fun `polling uses exponential backoff between polls`() = runBlocking {
        val backoffConfigPath = Files.createTempFile("easyai-rag-backoff-test", ".json")
        runBlocking {
            RagConfig.save(
                RagConfig(
                    enabled = true,
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    workspace = "ws-1",
                    indexPollIntervalMs = 100,
                    indexPollMaxMs = 2_000
                ),
                backoffConfigPath
            )
        }
        val backoffClient = EasyRagClient(backoffConfigPath)
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-b1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        // Return pending several times, then processed
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-b1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-b1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-b1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-b1","chunks_count":2}"""))

        val startTime = System.currentTimeMillis()
        val result = backoffClient.upsert(sampleDocument())
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(result.indexed)
        assertEquals(2, result.chunksCount)
        // Exponential backoff: 100 + 200 + 400 = 700ms of delay; allow margin for CI jitter
        assertTrue(elapsed in 500..1_500, "expected ~700ms exponential backoff, got ${elapsed}ms")
    }

    @Test
    fun `polling clamps zero interval to minimum to avoid busy loop`() = runBlocking {
        val clampConfigPath = Files.createTempFile("easyai-rag-clamp-test", ".json")
        runBlocking {
            RagConfig.save(
                RagConfig(
                    enabled = true,
                    baseUrl = server.url("/").toString().trimEnd('/'),
                    workspace = "ws-1",
                    indexPollIntervalMs = 0,
                    indexPollMaxMs = 1_000
                ),
                clampConfigPath
            )
        }
        val clampClient = EasyRagClient(clampConfigPath)
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"ok","docId":"doc-c1","message":"inserted"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"ok"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"pending","doc_id":"doc-c1"}"""))
        server.enqueue(MockResponse().setBody("""{"status":"processed","doc_id":"doc-c1","chunks_count":1}"""))

        val startTime = System.currentTimeMillis()
        val result = clampClient.upsert(sampleDocument())
        val elapsed = System.currentTimeMillis() - startTime

        assertTrue(result.indexed)
        // Zero interval must be clamped to MIN_POLL_INTERVAL_MS (100ms), not busy-loop
        assertTrue(elapsed >= 90, "expected clamped >=100ms delay between polls, got ${elapsed}ms")
    }

    // ------------------------------------------------------------------
    // Workspace tenant configuration
    // ------------------------------------------------------------------

    @Test
    fun `getWorkspaceConfig returns parsed config on 200`() = runTest {
        enqueueAuthStatus()
        server.enqueue(
            MockResponse().setBody("""
                {
                  "workspace": "ws-1",
                  "llmModel": "gpt-4o",
                  "llmApiKey": "sk-****",
                  "llmBaseUrl": "https://api.openai.com/v1",
                  "llmTemperature": 0.7,
                  "llmMaxTokens": 4096,
                  "embeddingModel": "text-embedding-3-small",
                  "embeddingDim": 1536,
                  "chunkSize": 1200,
                  "chunkOverlapSize": 100,
                  "language": "English",
                  "defaultTopK": 40,
                  "rerankEnabled": false,
                  "createdAt": "2026-01-01T00:00:00Z",
                  "updatedAt": "2026-01-02T00:00:00Z"
                }
            """.trimIndent())
        )

        val config = client.getWorkspaceConfig("ws-1")

        assertEquals("ws-1", config?.workspace)
        assertEquals("gpt-4o", config?.llmModel)
        assertEquals("sk-****", config?.llmApiKey)
        assertEquals(0.7f, config?.llmTemperature)
        assertEquals(4096, config?.llmMaxTokens)
        assertEquals(1536, config?.embeddingDim)
        assertEquals(1200, config?.chunkSize)
        assertEquals(100, config?.chunkOverlapSize)
        assertEquals(false, config?.rerankEnabled)

        server.takeRequest() // auth-status
        val getRequest = server.takeRequest()
        assertEquals("GET", getRequest.method)
        assertTrue(getRequest.path!!.contains("/api/admin/tenant-config"), getRequest.path)
        assertTrue(getRequest.path!!.contains("workspace=ws-1"), getRequest.path)
    }

    @Test
    fun `getWorkspaceConfig returns null on 404`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"error":"not found"}"""))

        val config = client.getWorkspaceConfig("unknown-ws")

        assertNull(config)
    }

    @Test
    fun `upsertWorkspaceConfig sends snake_case body and parses camelCase response`() = runTest {
        enqueueAuthStatus()
        server.enqueue(
            MockResponse().setBody("""
                {
                  "workspace": "ws-1",
                  "llmModel": "claude-3",
                  "llmTemperature": 0.0,
                  "updatedAt": "2026-01-03T00:00:00Z"
                }
            """.trimIndent())
        )

        val result = client.upsertWorkspaceConfig(
            RagWorkspaceConfigUpdate(
                workspace = "ws-1",
                llmModel = "claude-3",
                llmTemperature = 0.0f
            )
        )

        assertEquals("ws-1", result.workspace)
        assertEquals("claude-3", result.llmModel)

        server.takeRequest() // auth-status
        val postRequest = server.takeRequest()
        assertEquals("POST", postRequest.method)
        assertTrue(postRequest.path!!.contains("/api/admin/tenant-config"), postRequest.path)
        val body = postRequest.body.readUtf8()
        assertTrue(body.contains(""""workspace":"ws-1""""), body)
        assertTrue(body.contains(""""llm_model":"claude-3""""), body)
        assertTrue(body.contains(""""llm_temperature":0.0"""), body)
    }

    @Test
    fun `deleteWorkspaceConfig sends DELETE with workspace param`() = runTest {
        enqueueAuthStatus()
        server.enqueue(MockResponse().setBody("""{"status":"success"}"""))

        client.deleteWorkspaceConfig("ws-1")

        server.takeRequest() // auth-status
        val deleteRequest = server.takeRequest()
        assertEquals("DELETE", deleteRequest.method)
        assertTrue(deleteRequest.path!!.contains("/api/admin/tenant-config"), deleteRequest.path)
        assertTrue(deleteRequest.path!!.contains("workspace=ws-1"), deleteRequest.path)
    }

    @Test
    fun `getWorkspaceConfig returns null when disabled`() = runTest {
        val disabledConfigPath = Files.createTempFile("easyai-rag-disabled-test", ".json")
        runBlocking {
            RagConfig.save(RagConfig(enabled = false), disabledConfigPath)
        }
        val disabledClient = EasyRagClient(disabledConfigPath)

        val config = disabledClient.getWorkspaceConfig("ws-1")

        assertNull(config)
    }
}
