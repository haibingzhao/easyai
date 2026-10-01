package com.easy.easyai.skills.selection

import com.easy.easyai.common.util.SharedObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import tools.jackson.module.kotlin.readValue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HttpSkillSelectionClientTest {

    private val server = MockWebServer()
    private val client = HttpSkillSelectionClient()
    private val mapper = SharedObjectMapper.instance

    @org.junit.jupiter.api.BeforeAll
    fun start() {
        server.start()
    }

    @org.junit.jupiter.api.AfterAll
    fun stop() {
        server.shutdown()
    }

    private fun request(timeoutMs: Long = 5_000) = SystemOneSelectionRequest(
        baseUrl = server.url("/apps/anthropic").toString(),
        apiKey = "sk-test",
        modelId = "decision-model-preview",
        query = "帮我生成一张海报",
        instructions = "哪个技能最匹配？",
        criteria = mapOf("poster" to "图片/海报生成", "other" to "No skill is needed"),
        timeoutMs = timeoutMs
    )

    private fun decisionResponse(json: String, status: Int = 200) =
        MockResponse().setResponseCode(status).setHeader("Content-Type", "application/json").setBody(json)

    @Nested
    inner class HappyPath {

        @Test
        fun `posts the decision shape to the derived endpoint and reads the answer`() = runTest {
            server.enqueue(
                decisionResponse(
                    """{"model":"decision-model-preview","request_id":"r-1",
                       "answers":{"skill":{"type":"choice","choice":"poster",
                       "confidence":0.96,"probabilities":{"poster":0.98,"none":0.02}}},
                       "usage":{"input_tokens":33},"latency_ms":61.1}"""
                )
            )

            val decision = client.decide(request())

            assertEquals("poster", decision?.choice)
            assertEquals(0.96, decision?.confidence)
            val recorded = server.takeRequest()
            assertEquals("/compatible-mode/v1/systemone", recorded.path)
            assertEquals("Bearer sk-test", recorded.getHeader("Authorization"))
            val body = mapper.readValue<Map<String, Any?>>(recorded.body.readUtf8())
            assertEquals("decision-model-preview", body["model"])
            @Suppress("UNCHECKED_CAST")
            val questions = body["questions"] as Map<String, Map<String, Any?>>
            val skill = questions.getValue("skill")
            assertEquals("choice", skill["type"])
            @Suppress("UNCHECKED_CAST")
            assertEquals(setOf("poster", "other"), (skill["criteria"] as Map<String, String>).keys)
            @Suppress("UNCHECKED_CAST")
            assertEquals("帮我生成一张海报", (body["state"] as Map<String, String>)["query"])
        }

        @Test
        fun `missing confidence degrades to a null confidence`() = runTest {
            server.enqueue(decisionResponse("""{"answers":{"skill":{"type":"choice","choice":"poster"}}}"""))
            val decision = client.decide(request())
            assertEquals("poster", decision?.choice)
            assertNull(decision?.confidence)
        }
    }

    @Nested
    inner class Degradation {

        @Test
        fun `non-2xx degrades to null`() = runTest {
            server.enqueue(decisionResponse("""{"error":"unauthorized"}""", status = 401))
            assertNull(client.decide(request()))
        }

        @Test
        fun `response without the skill answer degrades to null`() = runTest {
            server.enqueue(decisionResponse("""{"answers":{"other":"x"}}"""))
            assertNull(client.decide(request()))
        }

        @Test
        fun `blank choice degrades to a null choice`() = runTest {
            server.enqueue(decisionResponse("""{"answers":{"skill":{"type":"choice","choice":"   ","confidence":0.9}}}"""))
            assertNull(client.decide(request())?.choice)
        }

        @Test
        fun `unparsable body degrades to null`() = runTest {
            server.enqueue(decisionResponse("not json at all"))
            assertNull(client.decide(request()))
        }

        @Test
        fun `slow gateway hits the timeout and degrades to null`() = runTest {
            server.enqueue(
                MockResponse()
                    .setHeader("Content-Type", "application/json")
                    .setBody("""{"answers":{"skill":{"choice":"poster","confidence":0.9}}}""")
                    .setBodyDelay(2, TimeUnit.SECONDS)
            )
            assertNull(client.decide(request(timeoutMs = 150)))
        }

        @Test
        fun `cancellation propagates instead of degrading`() = runTest {
            server.enqueue(MockResponse().setBody("{}").setBodyDelay(5, TimeUnit.SECONDS))
            var thrown: Throwable? = null
            val job = launch {
                try {
                    client.decide(request())
                } catch (e: Throwable) {
                    thrown = e
                }
            }
            // Let the child reach the real-HTTP suspension point before cancelling; cancelling an
            // unstarted child skips its body entirely and leaves `thrown` null.
            yield()
            job.cancelAndJoin()
            // If decide swallowed the cancellation it would return null and leave `thrown` unset.
            assertTrue(thrown is CancellationException, "expected cancellation, got $thrown")
        }

        @Test
        fun `endpoint derivation rejects non-urls and foreign schemes`() {
            assertNull(HttpSkillSelectionClient.systemOneEndpoint("not a url"))
            assertNull(HttpSkillSelectionClient.systemOneEndpoint("ftp://host/x"))
            assertNull(HttpSkillSelectionClient.systemOneEndpoint("https:///no-host"))
        }
    }

    @Nested
    inner class EndpointDerivation {

        @Test
        fun `keeps only scheme host and port`() {
            assertEquals(
                "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone",
                HttpSkillSelectionClient.systemOneEndpoint("https://token-plan.cn-beijing.maas.aliyuncs.com/apps/anthropic")
            )
            assertEquals(
                "https://llm-x.cn-beijing.maas.aliyuncs.com/compatible-mode/v1/systemone",
                HttpSkillSelectionClient.systemOneEndpoint("https://llm-x.cn-beijing.maas.aliyuncs.com/compatible-mode/v1")
            )
            assertEquals(
                "http://localhost:8080/compatible-mode/v1/systemone",
                HttpSkillSelectionClient.systemOneEndpoint("http://localhost:8080/anything/else")
            )
        }
    }
}
