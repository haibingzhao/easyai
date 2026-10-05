package com.easy.easyai.tools.media

import com.easy.easyai.core.media.MediaProviderSettings
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import org.springframework.core.io.ByteArrayResource
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.MultipartBodyBuilder
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import java.net.URI
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
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
                .bodyToMono<ByteArray>()
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

    /**
     * Transcribe audio; returns the text.
     *
     * Three routes share this entry, picked by model name:
     * - Whisper-compatible `POST {base}/audio/transcriptions` multipart upload;
     * - Qwen3-ASR-Flash, "OpenAI compatible" only over `POST {base}/chat/completions`
     *   (audio as a base64 `input_audio` data URL) — those gateways carry no
     *   transcriptions route at all and answer 404;
     * - Qwen-Audio-x.0-ASR-Flash / Fun-ASR-Flash, which the compatible-mode chat route
     *   rejects (400 — it serves Qwen3-ASR only); they speak the DashScope-native
     *   `POST {root}/api/v1/services/aigc/multimodal-generation/generation`.
     */
    suspend fun transcribe(audio: ByteArray, filename: String, language: String?): String {
        val model = settings.defaultModel.ifBlank { "whisper-1" }
        if (model.contains("asr", ignoreCase = true)) {
            return if (model.startsWith("qwen3-asr", ignoreCase = true)) {
                transcribeViaChat(audio, filename, model, language)
            } else {
                transcribeViaNative(audio, filename, model, language)
            }
        }
        val body = MultipartBodyBuilder().apply {
            part("file", NamedBytesResource(audio, filename))
            part("model", model)
            language?.takeIf { it.isNotBlank() }?.let { part("language", it) }
        }.build()
        val json = withTimeout(timeout) {
            client.post()
                .uri("$base/audio/transcriptions")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .headers { applyAuth(it) }
                .bodyValue(body)
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()
        }
        val node = MediaArtifacts.readJson(json)
        return node.path("text").asString(null)
            ?: throw IllegalStateException("transcription response carried no text")
    }

    private suspend fun transcribeViaChat(audio: ByteArray, filename: String, model: String, language: String?): String {
        val dataUrl = "data:${mimeForAudio(filename)};base64,${Base64.getEncoder().encodeToString(audio)}"
        val body = buildMap<String, Any> {
            put("model", model)
            put(
                "messages",
                listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "input_audio", "input_audio" to mapOf("data" to dataUrl))
                        )
                    )
                )
            )
            language?.takeIf { it.isNotBlank() }?.let { put("asr_options", mapOf("language" to it)) }
        }
        val json = postJson("$base/chat/completions", body)
        val content = MediaArtifacts.readJson(json)
            .path("choices").path(0).path("message").path("content").asString(null)
        return content?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ASR chat response carried no transcription text")
    }

    private suspend fun transcribeViaNative(audio: ByteArray, filename: String, model: String, language: String?): String {
        val dataUrl = "data:${mimeForAudio(filename)};base64,${Base64.getEncoder().encodeToString(audio)}"
        val format = filename.substringAfterLast('.', "wav").lowercase()
        val body = mapOf(
            "model" to model,
            "input" to mapOf(
                "messages" to listOf(
                    mapOf(
                        "role" to "user",
                        "content" to listOf(
                            mapOf("type" to "input_audio", "input_audio" to mapOf("data" to dataUrl))
                        )
                    )
                )
            ),
            "parameters" to buildMap {
                put("format", format)
                sampleRateOf(audio, format)?.let { put("sample_rate", it.toString()) }
                language?.takeIf { it.isNotBlank() }?.let { put("language_hints", listOf(it)) }
            }
        )
        val json = try {
            withTimeout(timeout) {
                client.post()
                    .uri("${nativeApiRoot()}/api/v1/services/aigc/multimodal-generation/generation")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers {
                        applyAuth(it)
                        it.set("X-DashScope-SSE", "disable")
                    }
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono<String>()
                    .awaitSingle()
            }
        } catch (e: WebClientResponseException) {
            // Segments with no detectable speech are answered 400 without a code/message error
            // body — pure silence returns a bare "{}", near-speech a well-formed empty
            // transcript. Both are results ("nothing heard"), not request failures.
            val text = runCatching { MediaArtifacts.readJson(e.responseBodyAsString) }.getOrNull()
                ?.let { node ->
                    node.path("output").path("text").asString(null)
                        ?: node.path("text").asString(null)
                        ?: if (node.has("code")) null else ""
                }
            if (text != null) return text
            throw providerError(e)
        }
        val text = MediaArtifacts.readJson(json).path("output").path("text").asString(null)
        return text ?: throw IllegalStateException("ASR response carried no output.text")
    }

    /** PCM sample rate from a RIFF/WAVE header's fmt chunk (bytes 24..28); null for anything else. */
    private fun sampleRateOf(audio: ByteArray, format: String): Int? {
        if (format != "wav" || audio.size < 28) return null
        if (String(audio, 0, 4, Charsets.US_ASCII) != "RIFF") return null
        if (String(audio, 8, 4, Charsets.US_ASCII) != "WAVE") return null
        return ByteBuffer.wrap(audio, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
            .takeIf { it in 8_000..192_000 }
    }

    /** The compatible-mode base (`…/compatible-mode/v1`) shares its host with the native `/api/v1` routes. */
    private fun nativeApiRoot(): String = base
        .substringBefore("/compatible-mode")
        .removeSuffix("/api/v1")
        .trimEnd('/')

    private suspend fun postJson(url: String, body: Any): String = withTimeout(timeout) {
        try {
            client.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_JSON)
                .headers { applyAuth(it) }
                .bodyValue(body)
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()
        } catch (e: WebClientResponseException) {
            throw providerError(e)
        }
    }

    /** Gateways explain rejections in the response body, which the exception message drops. */
    private fun providerError(e: WebClientResponseException): IllegalStateException {
        val detail = e.responseBodyAsString?.take(MAX_ERROR_BODY_CHARS)?.takeIf { it.isNotBlank() }
            ?: e.statusText
        return IllegalStateException("HTTP ${e.statusCode.value()}: $detail", e)
    }

    private fun mimeForAudio(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
        "wav" -> "audio/wav"
        "mp3" -> "audio/mpeg"
        "opus" -> "audio/opus"
        "aac" -> "audio/aac"
        "flac" -> "audio/flac"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "pcm" -> "audio/L16"
        else -> "application/octet-stream"
    }

    /** Download a provider-returned artifact URL, refusing internal hosts and oversized bodies. */
    private suspend fun download(url: String): ByteArray {
        MediaFetch.validateNotInternal(url)
        return withTimeout(timeout) {
            client.get()
                .uri(URI.create(url))
                .retrieve()
                .bodyToMono<ByteArray>()
                .awaitSingle()
        }
    }

    private fun applyAuth(headers: HttpHeaders) {
        if (settings.apiKey.isNotBlank()) headers.setBearerAuth(settings.apiKey)
    }

    companion object {
        private const val DEFAULT_BASE = "https://api.openai.com/v1"
        private const val MAX_IN_MEMORY = 32 * 1024 * 1024 // 32MB: large image/video responses fail fast, not OOM
        private const val MAX_ERROR_BODY_CHARS = 500
    }
}

/** ByteArrayResource carrying an upload filename, as multipart `file` parts require. */
private class NamedBytesResource(bytes: ByteArray, private val filename: String) : ByteArrayResource(bytes) {
    override fun getFilename(): String = filename
}
