package com.easy.easyai.tools.media

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.net.InetAddress
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/**
 * Shared download plumbing for fetching media bytes from an external URL.
 *
 * Redirects are never followed (a redirect could slip past the internal-address check), the body
 * is capped by the codec limit, and only public http(s) hosts are accepted — the same guards the
 * generation providers apply to their artifact links.
 */
internal object MediaFetch {

    private val client: WebClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY) }
        .build()

    /** Download [url]; returns the bytes plus the response Content-Type (null when absent). */
    suspend fun download(url: String, timeoutSeconds: Long = DEFAULT_TIMEOUT_SECONDS): Pair<ByteArray, String?> {
        validateNotInternal(url)
        return withTimeout(timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).seconds) {
            client.get()
                .uri(url)
                .exchangeToMono { response ->
                    val status = response.statusCode().value()
                    if (status !in 200..299) {
                        response.releaseBody().then(Mono.error(IllegalStateException("download failed: HTTP $status")))
                    } else {
                        val contentType = response.headers().contentType().map { it.toString() }.orElse(null)
                        response.bodyToMono<ByteArray>().map { bytes -> bytes to contentType }
                    }
                }
                .awaitSingle()
        }
    }

    /** Require an absolute http(s) URL whose host resolves to a non-internal address. */
    fun validateNotInternal(url: String) {
        val uri = URI(url)
        if (uri.scheme !in setOf("http", "https") || uri.host.isNullOrBlank()) {
            throw IllegalArgumentException("media URL must be an absolute http(s) URL")
        }
        val addr = InetAddress.getByName(uri.host)
        if (addr.isLoopbackAddress || addr.isLinkLocalAddress || addr.isSiteLocalAddress || addr.isAnyLocalAddress) {
            throw IllegalArgumentException("media URL resolves to an internal address")
        }
    }

    private const val MAX_IN_MEMORY = 32 * 1024 * 1024 // 32MB: oversized bodies fail fast, not OOM
    private const val DEFAULT_TIMEOUT_SECONDS = 60L
    private const val MAX_TIMEOUT_SECONDS = 900L
}
