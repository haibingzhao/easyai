package com.easy.easyai.tools.web

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.netty.http.client.HttpClient
import java.net.InetAddress
import java.net.URI
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
class WebFetchTool(metadata: ToolMetadata) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    /** Shared WebClient instance — avoids creating a new connection pool per request. */
    private val client: WebClient = WebClient.builder()
        .clientConnector(
            ReactorClientHttpConnector(
                HttpClient.create().followRedirect(false)
            )
        )
        .codecs { it.defaultCodecs().maxInMemorySize(MAX_RESPONSE_SIZE) }
        .build()

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
            validateUrlNotInternal(url)
        } catch (e: IllegalArgumentException) {
            return@withContext errorResult(e.message ?: "Invalid URL")
        }

        val format = (args["format"] as? String) ?: "markdown"
        if (format !in VALID_FORMATS) {
            return@withContext errorResult("Invalid format '$format'. Must be one of: ${VALID_FORMATS.joinToString()}")
        }

        val timeoutSec = ((args["timeout"] as? Number)?.toInt() ?: DEFAULT_TIMEOUT_SEC).coerceIn(1, MAX_TIMEOUT_SEC)

        onUpdate(ToolUpdate.Progress("Fetching $url"))

        try {
            val result = withTimeout(timeoutSec.seconds) {
                fetchUrl(url, format)
            }
            ToolResult(content = listOf(TextContent(result)))
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 403 && isCloudflareChallenge(e)) {
                logger.debug("Cloudflare challenge detected for {}, retrying with simplified UA", url)
                try {
                    val result = withTimeout(timeoutSec.seconds) {
                        fetchUrl(url, format, userAgent = FALLBACK_USER_AGENT)
                    }
                    ToolResult(content = listOf(TextContent(result)))
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

    private suspend fun fetchUrl(url: String, format: String, userAgent: String = BROWSER_USER_AGENT): String {
        val acceptHeader = acceptHeaderFor(format)

        val response = client.get()
            .uri(url)
            .header(HttpHeaders.USER_AGENT, userAgent)
            .header(HttpHeaders.ACCEPT, acceptHeader)
            .header(HttpHeaders.ACCEPT_LANGUAGE, "en-US,en;q=0.9")
            .retrieve()
            .bodyToMono(String::class.java)
            .awaitSingle()

        // Determine content type from the response (best-effort: we trust Accept negotiation)
        return convertContent(response, format)
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
        private val VALID_FORMATS = setOf("markdown", "text", "html")

        private fun acceptHeaderFor(format: String): String = when (format) {
            "markdown" -> "text/markdown;q=1.0, text/x-markdown;q=0.9, text/plain;q=0.8, text/html;q=0.7, */*;q=0.1"
            "text" -> "text/plain;q=1.0, text/markdown;q=0.9, text/html;q=0.8, */*;q=0.1"
            "html" -> "text/html;q=1.0, application/xhtml+xml;q=0.9, text/plain;q=0.8, text/markdown;q=0.7, */*;q=0.1"
            else -> "*/*"
        }

        /**
         * Validates that the URL does not resolve to an internal/private network address.
         * Prevents SSRF attacks via direct IP, DNS rebinding to localhost, etc.
         */
        private fun validateUrlNotInternal(url: String) {
            val uri = URI(url)
            val host = uri.host ?: throw IllegalArgumentException("Invalid URL: no host")
            val addr = InetAddress.getByName(host)
            if (addr.isLoopbackAddress() || addr.isLinkLocalAddress() ||
                addr.isSiteLocalAddress() || addr.isAnyLocalAddress()) {
                throw IllegalArgumentException("Access to internal network addresses is not allowed: $host")
            }
        }

        private fun isCloudflareChallenge(e: WebClientResponseException): Boolean {
            if (e.headers["cf-mitigated"]?.firstOrNull() == "challenge") return true
            val server = e.headers["server"]?.firstOrNull()?.lowercase()
            return server?.contains("cloudflare") == true
        }
    }
}
