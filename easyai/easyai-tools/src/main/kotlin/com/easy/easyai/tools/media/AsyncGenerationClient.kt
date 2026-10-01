package com.easy.easyai.tools.media

import com.easy.easyai.core.media.MediaProviderSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import reactor.netty.http.client.HttpClient
import tools.jackson.databind.JsonNode
import java.net.URI
import kotlin.time.Duration.Companion.seconds

/** What a bounded async-job wait ended with. */
internal sealed interface AsyncJobOutcome {
    /** Job finished; [url] points at the generated artifact. */
    data class Succeeded(val url: String) : AsyncJobOutcome

    /** Job failed terminally; [reason] is the provider message when present. */
    data class Failed(val reason: String?) : AsyncJobOutcome

    /** Still running when the polling window elapsed — hand [taskId] back to the model. */
    data class Pending(val taskId: String) : AsyncJobOutcome
}

/**
 * HTTP client for submit → poll → fetch async generation jobs (video / music), driven by one
 * [MediaProviderSettings] and a `{base}/{pathSegment}/generations` endpoint pair.
 *
 * Response shapes differ per gateway, so status and artifact-URL extraction probe a list of
 * candidate JSON paths (dotted, with optional `[i]` index segments) instead of assuming one vendor
 * contract. Auth is Bearer apiKey only.
 */
internal class AsyncGenerationClient(
    private val settings: MediaProviderSettings,
    private val pathSegment: String,
    private val artifactUrlPaths: List<String>
) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val base = settings.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE }
    private val timeout = settings.timeoutSeconds.coerceIn(1, 1800).seconds

    private val client: WebClient = WebClient.builder()
        .clientConnector(ReactorClientHttpConnector(HttpClient.create().followRedirect(false)))
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_IN_MEMORY) }
        .build()

    /** Submit a generation job; returns the provider task id, or null when the response carries none. */
    suspend fun submit(modelFallback: String, prompt: String): String? {
        val body = buildMap<String, Any> {
            put("model", settings.defaultModel.ifBlank { modelFallback })
            put("prompt", prompt)
        }
        val json = withTimeout(timeout) {
            client.post()
                .uri("$base/$pathSegment/generations")
                .contentType(MediaType.APPLICATION_JSON)
                .headers { applyAuth(it) }
                .bodyValue(body)
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()
        }
        val node = MediaArtifacts.readJson(json)
        return firstText(node, "task_id", "taskId", "id", "output.task_id", "data.task_id")
    }

    /**
     * Poll [taskId] until terminal or the bounded window elapses.
     *
     * [onProgress] is invoked on every still-running tick so the UI can show liveness.
     * A query error is treated as "not ready yet" and retried within the window.
     */
    suspend fun awaitTask(
        taskId: String,
        progressMessage: (String) -> String,
        onProgress: suspend (String) -> Unit
    ): AsyncJobOutcome {
        val terminal = withTimeoutOrNull(POLL_WINDOW_SECONDS.seconds) {
            var outcome: AsyncJobOutcome? = null
            while (outcome == null) {
                val node = query(taskId)
                if (node != null) {
                    outcome = when (statusOf(node)) {
                        JobStatus.SUCCEEDED ->
                            artifactUrlOf(node)?.let { AsyncJobOutcome.Succeeded(it) }
                                ?: AsyncJobOutcome.Failed("task succeeded but no artifact URL was present")
                        JobStatus.FAILED -> AsyncJobOutcome.Failed(failureReason(node))
                        JobStatus.RUNNING -> {
                            onProgress(progressMessage(taskId))
                            delay(POLL_INTERVAL_MS)
                            null
                        }
                    }
                } else {
                    delay(POLL_INTERVAL_MS)
                }
            }
            outcome
        }
        return terminal ?: AsyncJobOutcome.Pending(taskId)
    }

    /** Download a provider-returned artifact URL, refusing internal hosts and oversized bodies. */
    suspend fun download(url: String): ByteArray {
        MediaFetch.validateNotInternal(url)
        return withTimeout(timeout) {
            client.get().uri(URI.create(url)).retrieve().bodyToMono<ByteArray>().awaitSingle()
        }
    }

    private suspend fun query(taskId: String): JsonNode? = try {
        val json = withTimeout(timeout) {
            client.get()
                .uri("$base/$pathSegment/generations/$taskId")
                .headers { applyAuth(it) }
                .retrieve()
                .bodyToMono<String>()
                .awaitSingle()
        }
        MediaArtifacts.readJson(json)
    } catch (e: Exception) {
        logger.debug("{} task query for '{}' not ready yet: {}", pathSegment, taskId, e.message)
        null
    }

    private fun applyAuth(headers: HttpHeaders) {
        if (settings.apiKey.isNotBlank()) headers.setBearerAuth(settings.apiKey)
    }

    private enum class JobStatus { RUNNING, SUCCEEDED, FAILED }

    private fun statusOf(node: JsonNode): JobStatus {
        val raw = (firstText(node, "status", "task_status", "output.task_status", "data.status") ?: "RUNNING").uppercase()
        return when {
            raw.contains("SUCCEED") || raw.contains("COMPLET") || raw.contains("FINISH") || raw == "SUCCESS" -> JobStatus.SUCCEEDED
            raw.contains("FAIL") || raw.contains("ERROR") || raw.contains("CANCEL") -> JobStatus.FAILED
            else -> JobStatus.RUNNING
        }
    }

    private fun artifactUrlOf(node: JsonNode): String? = firstText(node, *artifactUrlPaths.toTypedArray())

    private fun failureReason(node: JsonNode): String? =
        firstText(node, "message", "output.message", "error.message", "reason")

    companion object {
        private const val DEFAULT_BASE = "https://api.openai.com/v1"
        private const val MAX_IN_MEMORY = 64 * 1024 * 1024 // 64MB: fail fast rather than OOM on a huge media body
        private const val POLL_WINDOW_SECONDS = 480L // 8 min bounded wait before handing the task back
        private const val POLL_INTERVAL_MS = 5_000L

        /** Reads the first present path; supports dotted paths and a single `[i]` index segment. */
        fun firstText(node: JsonNode, vararg paths: String): String? {
            for (path in paths) {
                var current: JsonNode = node
                var ok = true
                for (segment in path.split('.')) {
                    val name = segment.substringBefore('[')
                    val index = segment.substringAfter('[', "").substringBefore(']').toIntOrNull()
                    if (name.isNotEmpty()) current = current.path(name)
                    if (index != null) current = current.path(index)
                    if (current.isMissingNode || current.isNull) { ok = false; break }
                }
                if (ok && current.isTextual) return current.asText()
            }
            return null
        }
    }
}
