package com.easy.easyai.tools.web

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import reactor.netty.http.client.HttpClient
import java.net.URI
import java.net.UnknownHostException
import kotlin.time.Duration.Companion.seconds

/**
 * Parameters for [WebFetchTool].
 */
data class WebFetchParams(
    /** The HTTP or HTTPS URL to fetch content from. */
    val url: String,
    /** Output format: "markdown" (default), "text", or "html". */
    val format: String? = null,
    /** Optional timeout in seconds (max 120). Default: 30. */
    val timeout: Int? = null
)

/**
 * Fetches content from a URL and converts it to Markdown, plain text, or HTML.
 *
 * Ported from OpenCode's `webfetch.ts`:
 * - Browser-like User-Agent with Cloudflare 403 retry
 * - Format-dependent Accept headers with q-values
 * - 5 MB response size limit
 * - HTML→Markdown via [HtmlConverter]
 */
class WebFetchTool(
    metadata: ToolMetadata,
    private val client: WebClient = defaultClient(),
    private val urlValidator: (String) -> Unit = WebUrlSafety::validate
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = WebFetchParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String
        if (url.isNullOrBlank()) {
            return@withContext errorResult("Error: 'url' parameter is required")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext errorResult("URL must start with http:// or https://")
        }
        try {
            urlValidator(url)
        } catch (e: IllegalArgumentException) {
            return@withContext errorResult(e.message ?: "Invalid URL")
        } catch (e: UnknownHostException) {
            return@withContext errorResult("Could not resolve host: ${e.message}")
        }

        val format = (args["format"] as? String) ?: "markdown"
        if (format !in VALID_FORMATS) {
            return@withContext errorResult("Invalid format '$format'. Must be one of: ${VALID_FORMATS.joinToString()}")
        }

        val timeoutSec = ((args["timeout"] as? Number)?.toInt() ?: DEFAULT_TIMEOUT_SEC).coerceIn(1, MAX_TIMEOUT_SEC)

        onUpdate(ToolUpdate.Progress("Fetching $url"))

        try {
            val outcome = withTimeout(timeoutSec.seconds) {
                fetchUrl(url, format)
            }
            outcomeResult(outcome, url)
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 403 && isCloudflareChallenge(e)) {
                logger.debug("Cloudflare challenge detected for {}, retrying with simplified UA", url)
                try {
                    val outcome = withTimeout(timeoutSec.seconds) {
                        fetchUrl(url, format, userAgent = FALLBACK_USER_AGENT)
                    }
                    outcomeResult(outcome, url)
                } catch (retryEx: Exception) {
                    errorResult("Failed to fetch $url after Cloudflare retry: ${retryEx.message}")
                }
            } else {
                errorResult("HTTP ${e.statusCode.value()} fetching $url: ${e.message}")
            }
        } catch (_: TimeoutCancellationException) {
            errorResult("Request timed out after ${timeoutSec}s")
        } catch (e: Exception) {
            logger.warn("Failed to fetch {}: {}", url, e.message)
            errorResult("Failed to fetch $url: ${e.message}")
        }
    }

    private fun outcomeResult(outcome: FetchOutcome, requestedUrl: String): ToolResult = when (outcome) {
        is FetchOutcome.Success -> ToolResult(
            content = listOf(TextContent(outcome.content)),
            details = mapOf(
                "url" to outcome.finalUrl,
                "requestedUrl" to requestedUrl,
                "redirects" to outcome.redirects
            )
        )
        is FetchOutcome.Failure -> errorResult(outcome.message)
    }

    private suspend fun fetchUrl(
        url: String,
        format: String,
        userAgent: String = BROWSER_USER_AGENT
    ): FetchOutcome {
        val acceptHeader = acceptHeaderFor(format)
        var current = URI(url)
        var redirects = 0
        val chain = mutableListOf(current.toString())

        while (true) {
            val hop = fetchOnce(current, acceptHeader, userAgent)

            if (hop.status.value() in 300..399) {
                val next = resolveRedirectLocation(current, hop.location)
                    ?: return FetchOutcome.Failure(
                        "HTTP ${hop.status.value()} from $current has no usable Location header; " +
                            "the server may require a browser, or the redirect target is unsupported."
                    )
                if (redirects >= MAX_REDIRECTS) {
                    return FetchOutcome.Failure(
                        "Too many redirects (>$MAX_REDIRECTS): ${(chain + next.toString()).joinToString(" -> ")}"
                    )
                }
                try {
                    urlValidator(next.toString())
                } catch (e: IllegalArgumentException) {
                    return FetchOutcome.Failure("Redirect to $next was rejected: ${e.message}")
                } catch (e: UnknownHostException) {
                    return FetchOutcome.Failure("Redirect to $next could not be resolved: ${e.message}")
                }
                redirects++
                chain += next.toString()
                current = next
                continue
            }

            val content = convertContent(hop.content.orEmpty(), format)
            if (content.isBlank()) {
                val contentType = hop.contentType?.let { ", Content-Type: $it" }.orEmpty()
                return FetchOutcome.Failure(
                    "Web fetch from $current returned no usable text (HTTP ${hop.status.value()}$contentType). " +
                        "If the page requires JavaScript, fetch the raw file or API URL instead."
                )
            }
            return FetchOutcome.Success(
                content = if (redirects == 0) content
                else "[fetched $current (redirected from $url)]\n\n$content",
                finalUrl = current.toString(),
                redirects = redirects
            )
        }
    }

    private suspend fun fetchOnce(uri: URI, acceptHeader: String, userAgent: String): FetchHop =
        client.get()
            .uri(uri)
            .header(HttpHeaders.USER_AGENT, userAgent)
            .header(HttpHeaders.ACCEPT, acceptHeader)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
            .exchangeToMono { response ->
                val status = response.statusCode()
                when {
                    status.value() in 300..399 -> {
                        val location = response.headers().asHttpHeaders().getFirst(HttpHeaders.LOCATION)
                        response.releaseBody().thenReturn(FetchHop(status, null, null, location))
                    }
                    status.value() >= 400 ->
                        response.createException().flatMap { Mono.error<FetchHop>(it) }
                    else -> response.bodyToMono(String::class.java)
                        .defaultIfEmpty("")
                        .map { body ->
                            FetchHop(
                                status = status,
                                content = body,
                                contentType = response.headers().asHttpHeaders().contentType?.toString(),
                                location = null
                            )
                        }
                }
            }
            .awaitSingle()

    private data class FetchHop(
        val status: HttpStatusCode,
        val content: String?,
        val contentType: String?,
        val location: String?
    )

    private sealed interface FetchOutcome {
        data class Success(val content: String, val finalUrl: String, val redirects: Int) : FetchOutcome
        data class Failure(val message: String) : FetchOutcome
    }

    /**
     * Converts raw response content based on requested format.
     * If the response looks like HTML and format is markdown/text, converts it.
     */
    private fun convertContent(content: String, format: String): String {
        val looksLikeHtml = content.trimStart().startsWith("<!DOCTYPE", ignoreCase = true)
                || content.trimStart().startsWith("<html", ignoreCase = true)
                || content.contains("<head", ignoreCase = true)
                || content.contains("<body", ignoreCase = true)

        return when (format) {
            "markdown" -> if (looksLikeHtml) HtmlConverter.toMarkdown(content) else content
            "text" -> if (looksLikeHtml) HtmlConverter.toText(content) else content
            "html" -> content
            else -> content
        }
    }

    companion object {
        private const val BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36"
        private const val FALLBACK_USER_AGENT = "easyai"
        private const val DEFAULT_TIMEOUT_SEC = 30
        private const val MAX_TIMEOUT_SEC = 120
        private const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024 // 5MB
        private const val MAX_REDIRECTS = 5
        private val VALID_FORMATS = setOf("markdown", "text", "html")

        /** Shared WebClient: one connection pool for every fetch, redirects handled hop by hop in [fetchUrl]. */
        private fun defaultClient(): WebClient = WebClient.builder()
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().followRedirect(false)
                )
            )
            .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_SIZE) }
            .build()

        /**
         * Resolves a Location header against the current URL, or null when it cannot be followed
         * safely. Kept as a thin delegate for callers that already import the tool class.
         */
        internal fun resolveRedirectLocation(current: URI, location: String?): URI? =
            WebUrlSafety.resolveRedirect(current, location)

        private fun acceptHeaderFor(format: String): String = when (format) {
            "markdown" -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1"
            "text" -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1"
            "html" -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1"
            else -> "*/*"
        }

        private fun isCloudflareChallenge(e: WebClientResponseException): Boolean {
            if (e.headers["cf-mitigated"]?.firstOrNull() == "challenge") return true
            val server = e.headers["server"]?.firstOrNull()?.lowercase()
            return server?.contains("cloudflare") == true
        }
    }
}
