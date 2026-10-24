package com.easy.easyai.web.service.skillgen

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.reactive.function.client.ClientRequest
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.HexFormat
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SkillArchiveFetcherTest {

    private val zipBytes = byteArrayOf(0x50, 0x4B, 0x03, 0x04) + "payload".toByteArray()

    private fun response(status: HttpStatus, body: ByteArray = ByteArray(0)): ClientResponse =
        ClientResponse.create(status)
            .body(
                if (body.isEmpty()) Flux.empty()
                else Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(body))
            )
            .build()

    private fun redirect(status: HttpStatus, location: String?): ClientResponse =
        ClientResponse.create(status)
            .apply { location?.let { header(HttpHeaders.LOCATION, it) } }
            .build()

    private fun fetcher(
        validator: (String) -> Unit = {},
        handler: (ClientRequest) -> Mono<ClientResponse>
    ) = SkillArchiveFetcher(
        client = WebClient.builder().exchangeFunction { handler(it) }.build(),
        urlValidator = validator
    )

    private fun fetch(fetcher: SkillArchiveFetcher, url: String, maxBytes: Long = 1024): FetchedArchive =
        runBlocking { fetcher.fetch(url, maxBytes) }

    private fun sha256(bytes: ByteArray): String =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

    @Nested
    inner class Download {

        @Test
        fun `returns the bytes the final url and their sha256`() {
            val requested = mutableListOf<String>()
            val fetcher = fetcher { request ->
                requested += request.url().toString()
                when (request.url().host) {
                    "www.futunn.com" -> Mono.just(redirect(HttpStatus.FOUND, "https://static.futunn.com/futu.zip"))
                    else -> Mono.just(response(HttpStatus.OK, zipBytes))
                }
            }

            val archive = fetch(fetcher, "https://www.futunn.com/skills/futu.zip")

            assertEquals(zipBytes.toList(), archive.bytes.toList())
            assertEquals("https://static.futunn.com/futu.zip", archive.finalUrl)
            assertEquals(sha256(zipBytes), archive.sha256)
            assertEquals(
                listOf("https://www.futunn.com/skills/futu.zip", "https://static.futunn.com/futu.zip"),
                requested
            )
        }

        @Test
        fun `a non zip body is refused even on a 200`() {
            val fetcher = fetcher { Mono.just(response(HttpStatus.OK, "<html>install guide</html>".toByteArray())) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip") }

            assertEquals(SkillArchiveCodes.NOT_A_ZIP, error.code)
            assertContains(error.message!!, "PK zip signature")
        }

        @Test
        fun `an empty body is a fetch failure`() {
            val fetcher = fetcher { Mono.just(response(HttpStatus.OK)) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertContains(error.message!!, "empty body")
        }

        @Test
        fun `an http error reports the status`() {
            val fetcher = fetcher { Mono.just(response(HttpStatus.NOT_FOUND)) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertContains(error.message!!, "HTTP 404")
        }

        @Test
        fun `the byte cap is enforced while streaming`() {
            val fetcher = fetcher { Mono.just(response(HttpStatus.OK, zipBytes + ByteArray(64))) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip", maxBytes = 8) }

            assertEquals(SkillArchiveCodes.TOO_LARGE, error.code)
            assertContains(error.message!!, "8-byte")
        }

        @Test
        fun `a non http url is refused before any request`() {
            var calls = 0
            val fetcher = fetcher { calls++; Mono.just(response(HttpStatus.OK, zipBytes)) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "file:///etc/passwd") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertEquals(0, calls)
        }
    }

    @Nested
    inner class Ssrf {

        @Test
        fun `the initial url is validated`() {
            val fetcher = fetcher(
                validator = { throw IllegalArgumentException("internal address") }
            ) { Mono.just(response(HttpStatus.OK, zipBytes)) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "http://10.0.0.1/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertContains(error.message!!, "Refused")
            assertContains(error.message!!, "internal address")
        }

        @Test
        fun `every redirect hop is validated`() {
            val validated = mutableListOf<String>()
            val fetcher = fetcher(
                validator = { url ->
                    validated += url
                    if (url.contains("169.254.169.254")) throw IllegalArgumentException("internal address")
                }
            ) { Mono.just(redirect(HttpStatus.FOUND, "http://169.254.169.254/latest/meta-data")) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertEquals(
                listOf("https://example.com/x.zip", "http://169.254.169.254/latest/meta-data"),
                validated
            )
        }

        @Test
        fun `a redirect without a location is a fetch failure`() {
            val fetcher = fetcher { Mono.just(redirect(HttpStatus.FOUND, null)) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertContains(error.message!!, "no usable Location")
        }

        @Test
        fun `a redirect to a non http scheme is refused`() {
            val fetcher = fetcher { Mono.just(redirect(HttpStatus.FOUND, "file:///etc/passwd")) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertContains(error.message!!, "no usable Location")
        }

        @Test
        fun `six redirects are too many`() {
            var hops = 0
            val fetcher = fetcher {
                hops++
                Mono.just(redirect(HttpStatus.FOUND, "/hop/$hops"))
            }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://example.com/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertContains(error.message!!, "Too many redirects")
            assertEquals(6, hops)
        }

        @Test
        fun `an unresolvable host is reported`() {
            val fetcher = fetcher(
                validator = { throw UnknownHostException("does-not-exist.invalid") }
            ) { Mono.just(response(HttpStatus.OK, zipBytes)) }

            val error = assertFailsWith<SkillArchiveException> { fetch(fetcher, "https://does-not-exist.invalid/x.zip") }

            assertEquals(SkillArchiveCodes.FETCH_FAILED, error.code)
            assertContains(error.message!!, "Could not resolve does-not-exist.invalid")
        }
    }
}
