package com.easy.easyai.web.service.skillgen

import com.easy.easyai.tools.web.WebUrlSafety
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientRequestException
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.net.URI
import java.net.UnknownHostException
import java.security.MessageDigest
import java.util.HexFormat

/** One downloaded archive: its exact bytes, the URL that finally served them, and their SHA-256. */
internal data class FetchedArchive(
    val bytes: ByteArray,
    val finalUrl: String,
    val sha256: String
)

/** Error codes the model and the HTTP layer branch on; keep them stable. */
internal object SkillArchiveCodes {
    const val FETCH_FAILED = "ARCHIVE_FETCH_FAILED"
    const val NOT_A_ZIP = "ARCHIVE_NOT_A_ZIP"
    const val TOO_LARGE = "ARCHIVE_TOO_LARGE"
    const val NO_SKILL = "ARCHIVE_NO_SKILL"
}

internal class SkillArchiveException(val code: String, message: String) : Exception(message)

/**
 * Downloads a skill archive from a user-supplied URL with the same admission rules as `webfetch`:
 * plain http(s) only, every redirect hop re-checked against internal addresses, a bounded redirect
 * count and a hard byte cap enforced while streaming.
 *
 * The body is validated by its zip signature, never by `Content-Type`: servers routinely serve
 * `.zip` as `application/octet-stream` or `text/plain`.
 */
internal class SkillArchiveFetcher(
    private val client: WebClient = defaultClient(),
    private val urlValidator: (String) -> Unit = WebUrlSafety::validate
) {

    suspend fun fetch(url: String, maxBytes: Long): FetchedArchive = withContext(Dispatchers.IO) {
        if (!WebUrlSafety.isHttpUrl(url)) {
            throw SkillArchiveException(SkillArchiveCodes.FETCH_FAILED, "Only http(s) URLs can be downloaded: $url")
        }
        val limit = maxBytes.coerceIn(1L, SKILL_ARCHIVE_MAX_BYTES)
        try {
            withTimeout(REQUEST_TIMEOUT_MS) { follow(url, limit) }
        } catch (e: TimeoutCancellationException) {
            throw SkillArchiveException(
                SkillArchiveCodes.FETCH_FAILED,
                "Downloading $url timed out after ${REQUEST_TIMEOUT_MS / 1000}s"
            )
        } catch (e: DataBufferLimitException) {
            throw SkillArchiveException(
                SkillArchiveCodes.TOO_LARGE,
                "The archive at $url exceeds the $limit-byte limit"
            )
        } catch (e: WebClientResponseException) {
            throw SkillArchiveException(
                SkillArchiveCodes.FETCH_FAILED,
                "HTTP ${e.statusCode.value()} downloading $url"
            )
        } catch (e: WebClientRequestException) {
            throw SkillArchiveException(
                SkillArchiveCodes.FETCH_FAILED,
                "Could not download $url: ${e.message}"
            )
        }
    }

    private suspend fun follow(url: String, limit: Long): FetchedArchive {
        var current = URI(url)
        var redirects = 0
        val chain = mutableListOf(url)
        while (true) {
            validate(current)
            val hop = fetchOnce(current, limit)
            if (hop.status.value() in 300..399) {
                val next = WebUrlSafety.resolveRedirect(current, hop.location)
                    ?: throw SkillArchiveException(
                        SkillArchiveCodes.FETCH_FAILED,
                        "HTTP ${hop.status.value()} from $current has no usable Location header"
                    )
                if (redirects >= MAX_REDIRECTS) {
                    throw SkillArchiveException(
                        SkillArchiveCodes.FETCH_FAILED,
                        "Too many redirects (>$MAX_REDIRECTS): ${(chain + next.toString()).joinToString(" -> ")}"
                    )
                }
                redirects++
                chain += next.toString()
                current = next
                continue
            }
            val bytes = hop.bytes
                ?: throw SkillArchiveException(SkillArchiveCodes.FETCH_FAILED, "The server returned an empty body for $current")
            if (!isZip(bytes)) {
                throw SkillArchiveException(
                    SkillArchiveCodes.NOT_A_ZIP,
                    "$current did not return a zip archive (no PK zip signature)"
                )
            }
            return FetchedArchive(bytes, current.toString(), sha256Hex(bytes))
        }
    }

    private fun validate(uri: URI) {
        try {
            urlValidator(uri.toString())
        } catch (e: IllegalArgumentException) {
            throw SkillArchiveException(SkillArchiveCodes.FETCH_FAILED, "Refused $uri: ${e.message}")
        } catch (e: UnknownHostException) {
            throw SkillArchiveException(
                SkillArchiveCodes.FETCH_FAILED,
                "Could not resolve ${uri.host}: ${e.message}"
            )
        }
    }

    private suspend fun fetchOnce(uri: URI, limit: Long): Hop =
        client.get()
            .uri(uri)
            .header(HttpHeaders.USER_AGENT, USER_AGENT)
            .header(HttpHeaders.ACCEPT, "application/zip, application/octet-stream, */*;q=0.8")
            .exchangeToMono { response ->
                val status = response.statusCode()
                when {
                    status.value() in 300..399 -> {
                        val location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION)
                        response.releaseBody().thenReturn(Hop(status, null, location))
                    }
                    status.value() >= 400 ->
                        response.createException().flatMap { Mono.error<Hop>(it) }
                    else -> DataBufferUtils.join(response.bodyToFlux(DataBuffer::class.java), limit.toInt())
                        .map { joined ->
                            try {
                                val bytes = ByteArray(joined.readableByteCount()).also { joined.read(it) }
                                Hop(status, bytes, null)
                            } finally {
                                DataBufferUtils.release(joined)
                            }
                        }
                        // An empty stream emits nothing at all; keep the empty-body case distinct
                        // from "not a zip" so the error text can say what actually happened.
                        .defaultIfEmpty(Hop(status, null, null))
                }
            }
            .awaitSingle()

    private data class Hop(
        val status: HttpStatusCode,
        val bytes: ByteArray?,
        val location: String?
    )

    private companion object {
        private const val REQUEST_TIMEOUT_MS = 60_000L
        private const val MAX_REDIRECTS = 5

        /**
         * The same browsing UA as `webfetch`: CDNs that gate on a bot-looking client otherwise answer
         * a plain Java/Reactor UA with 403 for archives a browser downloads fine.
         */
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"

        private val ZIP_MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

        private fun isZip(bytes: ByteArray): Boolean =
            bytes.size >= ZIP_MAGIC.size && ZIP_MAGIC.indices.all { bytes[it] == ZIP_MAGIC[it] }

        private fun sha256Hex(bytes: ByteArray): String =
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes))

        private fun defaultClient(): WebClient = WebClient.builder()
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().followRedirect(false)
                )
            )
            .codecs { it.defaultCodecs().maxInMemorySize(SKILL_ARCHIVE_MAX_BYTES.toInt()) }
            .build()
    }
}

/** Upper bound on a downloaded archive, independent of the configured package cap. */
internal const val SKILL_ARCHIVE_MAX_BYTES = 20L * 1024 * 1024
