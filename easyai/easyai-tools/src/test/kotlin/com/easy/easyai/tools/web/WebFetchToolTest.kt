package com.easy.easyai.tools.web

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.net.URI
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WebFetchToolTest {

    private val metadata = ToolMetadata(name = "webfetch", description = "test", permissionCategory = "web")

    private fun tool(
        validator: (String) -> Unit = {},
        handler: (ClientRequest) -> Mono<ClientResponse>
    ): WebFetchTool = WebFetchTool(
        metadata = metadata,
        client = WebClient.builder().exchangeFunction { handler(it) }.build(),
        urlValidator = validator
    )

    private fun response(status: HttpStatus, body: String = "", contentType: String? = null): ClientResponse =
        ClientResponse.create(status)
            .apply {
                contentType?.let { header(HttpHeaders.CONTENT_TYPE, it) }
                body(body)
            }
            .build()

    private fun redirect(status: HttpStatus, location: String?): ClientResponse =
        ClientResponse.create(status)
            .apply { location?.let { header(HttpHeaders.LOCATION, it) } }
            .build()

    private fun execute(tool: WebFetchTool, url: String, format: String? = null): ToolResult = runBlocking {
        tool.execute(
            agentContext = AgentContext(agentId = "test"),
            toolCallId = "call-test",
            args = buildMap {
                put("url", url)
                format?.let { put("format", it) }
            },
            coroutineScope = CoroutineScope(Job())
        )
    }

    private fun text(result: ToolResult): String =
        result.content.filterIsInstance<TextContent>().joinToString("") { it.text }

    @Nested
    inner class `redirects` {

        @Test
        fun `follows a 302 and reports the final url`() {
            val requested = mutableListOf<String>()
            val tool = tool { request ->
                requested += request.url().toString()
                when (request.url().host) {
                    "www.futunn.com" -> Mono.just(
                        redirect(HttpStatus.FOUND, "https://static.futunn.com/upload/ai/futu-install.md?_=1")
                    )
                    "static.futunn.com" -> Mono.just(
                        response(HttpStatus.OK, "# Futu\n\nInstall guide", MediaType.TEXT_MARKDOWN.toString())
                    )
                    else -> Mono.error(IllegalStateException("unexpected ${request.url()}"))
                }
            }

            val result = execute(tool, "https://www.futunn.com/skills/futu-install.md")

            assertFalse(result.isError)
            assertEquals(
                listOf(
                    "https://www.futunn.com/skills/futu-install.md",
                    "https://static.futunn.com/upload/ai/futu-install.md?_=1"
                ),
                requested
            )
            assertEquals("https://static.futunn.com/upload/ai/futu-install.md?_=1", result.details["url"])
            assertEquals("https://www.futunn.com/skills/futu-install.md", result.details["requestedUrl"])
            assertEquals(1, result.details["redirects"])
            assertTrue(text(result).startsWith("[fetched https://static.futunn.com/upload/ai/futu-install.md?_=1"), text(result))
            assertTrue(text(result).contains("# Futu"), text(result))
        }

        @Test
        fun `discards the redirect body instead of returning it as content`() {
            val tool = tool { request ->
                if (request.url().path == "/start") {
                    Mono.just(
                        response(
                            HttpStatus.FOUND,
                            "<!DOCTYPE html><html><head><meta http-equiv=\"refresh\" content=\"0; url=/next\"></head></html>",
                            MediaType.TEXT_HTML.toString()
                        ).mutate().header(HttpHeaders.LOCATION, "/next").build()
                    )
                } else {
                    Mono.just(response(HttpStatus.OK, "final content"))
                }
            }

            val result = execute(tool, "https://example.com/start")

            assertFalse(result.isError)
            assertTrue(text(result).contains("final content"), text(result))
            assertFalse(text(result).contains("refresh"), "redirect body must not leak into content")
        }

        @Test
        fun `resolves relative protocol-relative and absolute locations`() {
            val current = URI("https://www.example.com/skills/futu-install.md")

            assertEquals(URI("https://www.example.com/raw/x.md"), WebFetchTool.resolveRedirectLocation(current, "../raw/x.md"))
            assertEquals(URI("https://cdn.example.com/x.md"), WebFetchTool.resolveRedirectLocation(current, "//cdn.example.com/x.md"))
            assertEquals(URI("https://other.example.com/x.md"), WebFetchTool.resolveRedirectLocation(current, "https://other.example.com/x.md"))
            assertNull(WebFetchTool.resolveRedirectLocation(current, "javascript:alert(1)"))
            assertNull(WebFetchTool.resolveRedirectLocation(current, "file:///etc/passwd"))
            assertNull(WebFetchTool.resolveRedirectLocation(current, "   "))
            assertNull(WebFetchTool.resolveRedirectLocation(current, null))
        }

        @Test
        fun `rejects a redirect target refused by the validator`() {
            val requested = mutableListOf<String>()
            val tool = tool(
                handler = { request ->
                    requested += request.url().toString()
                    Mono.just(redirect(HttpStatus.FOUND, "http://169.254.169.254/latest/meta-data"))
                },
                validator = { url ->
                    if (url.contains("169.254.169.254")) throw IllegalArgumentException("internal address")
                }
            )

            val result = execute(tool, "https://example.com/start")

            assertTrue(result.isError)
            assertTrue(text(result).contains("rejected"), text(result))
            assertEquals(listOf("https://example.com/start"), requested)
        }

        @Test
        fun `six redirects are too many`() {
            var hops = 0
            val tool = tool {
                hops++
                Mono.just(redirect(HttpStatus.FOUND, "/hop/$hops"))
            }

            val result = execute(tool, "https://example.com/start")

            assertTrue(result.isError)
            assertTrue(text(result).contains("Too many redirects"), text(result))
            assertEquals(6, hops)
        }

        @Test
        fun `five redirects are allowed`() {
            var hops = 0
            val tool = tool {
                hops++
                if (hops <= 5) Mono.just(redirect(HttpStatus.FOUND, "/hop/$hops"))
                else Mono.just(response(HttpStatus.OK, "done"))
            }

            val result = execute(tool, "https://example.com/start")

            assertFalse(result.isError)
            assertTrue(text(result).contains("done"), text(result))
            assertEquals(6, hops)
        }

        @Test
        fun `missing location is an error`() {
            val tool = tool { Mono.just(redirect(HttpStatus.FOUND, null)) }

            val result = execute(tool, "https://example.com/start")

            assertTrue(result.isError)
            assertTrue(text(result).contains("no usable Location"), text(result))
        }
    }

    @Nested
    inner class `empty content` {

        @Test
        fun `an empty 200 body is an error`() {
            val tool = tool { Mono.just(response(HttpStatus.OK, "")) }

            val result = execute(tool, "https://example.com/start")

            assertTrue(result.isError)
            assertTrue(text(result).contains("no usable text"), text(result))
        }

        @Test
        fun `a meta-refresh only page is an error`() {
            val tool = tool {
                Mono.just(
                    response(
                        HttpStatus.OK,
                        "<!DOCTYPE html><html><head><meta http-equiv=\"refresh\" content=\"0; url=/next\"></head></html>",
                        MediaType.TEXT_HTML.toString()
                    )
                )
            }

            val result = execute(tool, "https://example.com/start")

            assertTrue(result.isError)
            assertTrue(text(result).contains("no usable text"), text(result))
        }
    }

    @Nested
    inner class `cloudflare retry` {

        @Test
        fun `403 from cloudflare retries with the fallback user agent`() {
            val userAgents = mutableListOf<String?>()
            val tool = tool { request ->
                val userAgent = request.headers().getFirst(HttpHeaders.USER_AGENT)
                userAgents += userAgent
                if (userAgent?.startsWith("Mozilla") == true) {
                    Mono.just(
                        ClientResponse.create(HttpStatus.FORBIDDEN)
                            .header("server", "cloudflare")
                            .body("challenge")
                            .build()
                    )
                } else {
                    Mono.just(response(HttpStatus.OK, "fetched"))
                }
            }

            val result = execute(tool, "https://example.com/start")

            assertFalse(result.isError)
            assertTrue(text(result).contains("fetched"), text(result))
            assertEquals(2, userAgents.size)
            assertTrue(userAgents[0]?.startsWith("Mozilla") == true)
            assertEquals("easyai", userAgents[1])
        }

        @Test
        fun `a non-cloudflare 403 is reported as an http error`() {
            val tool = tool {
                Mono.just(ClientResponse.create(HttpStatus.FORBIDDEN).body("nope").build())
            }

            val result = execute(tool, "https://example.com/start")

            assertTrue(result.isError)
            assertTrue(text(result).contains("HTTP 403"), text(result))
        }
    }
}
