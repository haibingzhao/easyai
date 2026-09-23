package com.easy.easyai.tools.media

import com.easy.easyai.core.media.MediaProviderSettings
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import kotlin.time.Duration.Companion.seconds

/**
 * HTTP client for OpenAI-compatible media generation, driven by one [MediaProviderSettings].
 *
 * Covers the request/response shapes shared by OpenAI itself and the many gateways that mimic it:
 * - image:   `POST {base}/images/generations` → `data[].b64_json` (preferred) or `data[].url`
 * - speech:  `POST {base}/audio/speech` → raw audio bytes in the response body
 *
 * Reactive calls are not wrapped in `Dispatchers.IO` (WebClient/Netty own their threads); only the
 * caller's storage writes are IO-bound. Vendor SDKs are deliberately avoided so a new compatible
 * provider needs only a settings row, never code.
 */
class OpenAiCompatibleClient(private val settings: MediaProviderSettings) {

    private val base: String = settings.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE }

    private val client: WebClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY) }
        .build()

    private val timeout = settings.timeoutSeconds.coerceIn(1, 900).seconds

    /** Generate images; returns produced binaries already decoded from base64 (or downloaded). */
    suspend fun generateImages(prompt: String, count: Int, size: String?): List<Pair<ByteArray, String>> {
        val body = buildMap<String, Any> {
            put("model", settings.defaultModel.ifBlank { "gpt-image-1" })
            put("prompt", prompt)
            put("n", count.coerceIn(1, 10))
            put("response_format", "b64_json")
            if (!size.isNullOrBlank()) put("size", size)
        }
        val responseJson = withTimeout(timeout) {
            client.post()
                .uri("$base/images/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { applyAuth(it) }
                .bodyValue(body)
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()
        }

        val data = MediaArtifacts.readJson(responseJson).path("data")
        val out = ArrayList<Pair<ByteArray, String>>(data.size())
        for (node in data) {
            val b64 = node.path("b64_json").asString(null)
            if (!b64.isNullOrBlank()) {
                out.add(MediaArtifacts.decodeBase64(b64) to "image/png")
                continue
            }
            val url = node.path("url").asString(null)
            if (!url.isNullOrBlank()) out.add(download(url) to "image/png")
        }
        return out
    }

    /** Synthesize speech; returns raw audio bytes and their media type. */
    suspend fun synthesize(text: String, voice: String?, format: String?): Pair<ByteArray, String> {
        val responseFormat = format?.takeIf { it.isNotBlank() } ?: "mp3"
        val body = buildMap<String, Any> {
            put("model", settings.defaultModel.ifBlank { "tts-1" })
            put("input", text)
            put("voice", voice?.takeIf { it.isNotBlank() } ?: "alloy")
            put("response_format", responseFormat)
        }
        val bytes = withTimeout(timeout) {
            client.post()
                .uri("$base/audio/speech")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { applyAuth(it) }
                .bodyValue(body)
                .retrieve()
                .bodyToMono(ByteArray::class.java)
                .awaitSingle()
        }

        val mime = when (responseFormat) {
            "wav" -> "audio/wav"
            "opus" -> "audio/opus"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            else -> "audio/mpeg"
        }
        return bytes to mime
    }

    /** Download a provider-returned artifact URL, refusing internal hosts and oversized bodies. */
    private suspend fun download(url: String): ByteArray {
        MediaFetch.validateNotInternal(url)
        return withTimeout(timeout) {
            client.get()
                .uri(url)
                .retrieve()
                .bodyToMono<ByteArray>()
                .awaitSingle()
        }
    }

    private fun applyAuth(headers: HttpHeaders) {
        if (settings.apiKey.isNotBlank()) headers.setBearerAuth(settings.apiKey)
        settings.accessKeyId.takeIf { it.isNotBlank() }?.let { headers.set("X-Access-Key-Id", it) }
        settings.accessKeySecret.takeIf { it.isNotBlank() }?.let { headers.set("X-Access-Key-Secret", it) }
    }

    companion object {
        private const val DEFAULT_BASE = "https://api.openai.com/v1"
        private const val MAX_IN_MEMORY = 32 * 1024 * 1024 // 32MB: large image/video responses fail fast, not OOM
    }
}
