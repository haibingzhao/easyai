package com.easy.easyai.tools.web

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import kotlinx.coroutines.*
import kotlinx.coroutines.reactor.awaitSingle
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.bodyToMono
import kotlin.time.Duration.Companion.seconds

/**
 * Parameters for [WebSearchTool].
 */
data class WebSearchParams(
    /** The search query. */
    val query: String,
    /** Number of results to return (default: 8, max: 20). */
    val numResults: Int? = null,
    /** Live crawl mode: "fallback" or "preferred" (default: "fallback"). */
    val livecrawl: String? = null,
    /** Search type: "auto", "fast", or "deep" (default: "auto"). */
    val type: String? = null,
    /** Max characters for LLM-optimized context string (default: 10000, max: 50000). */
    val contextMaxCharacters: Int? = null
)

/**
 * Pluggable search provider interface.
 * Each provider implements a specific search backend (Exa, Parallel, etc.).
 */
interface WebSearchProvider {
    val providerName: String
    val label: String
    suspend fun search(query: String, params: WebSearchParams, sessionId: String): String?
}

/**
 * Exa search provider using MCP JSON-RPC 2.0 protocol.
 * Ported from OpenCode's `mcp-websearch.ts`.
 */
class ExaSearchProvider(
    private val client: WebClient,
    private val apiKey: String?
) : WebSearchProvider {
    override val providerName = "exa"
    override val label = "Exa Web Search"

    // Security note: Exa MCP API requires the API key as a URL query parameter.
    // This means the key may appear in HTTP access logs and proxy logs.
    // Prefer ParallelSearchProvider (Bearer header) when possible.
    private val mcpUrl: String
        get() = if (apiKey != null) "$EXA_MCP_URL?exaApiKey=$apiKey" else EXA_MCP_URL

    override suspend fun search(query: String, params: WebSearchParams, sessionId: String): String? {
        val arguments = mutableMapOf<String, Any?>(
            "query" to query,
            "type" to (params.type ?: "auto"),
            "numResults" to (params.numResults ?: 8),
            "livecrawl" to (params.livecrawl ?: "fallback")
        )
        if (params.contextMaxCharacters != null) {
            arguments["contextMaxCharacters"] = params.contextMaxCharacters
        }

        val requestBody = buildMcpRequest("web_search_exa", arguments)
        return callMcp(mcpUrl, requestBody)
    }

    private suspend fun callMcp(url: String, body: Map<String, Any?>): String? {
        val response = client.post()
            .uri(url)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()

        return parseMcpResponse(response)
    }

    companion object {
        const val EXA_MCP_URL = "https://mcp.exa.ai/mcp"
    }
}

/**
 * Parallel search provider using MCP JSON-RPC 2.0 protocol.
 */
class ParallelSearchProvider(
    private val client: WebClient,
    private val apiKey: String?
) : WebSearchProvider {
    override val providerName = "parallel"
    override val label = "Parallel Web Search"

    override suspend fun search(query: String, params: WebSearchParams, sessionId: String): String? {
        val arguments = mapOf<String, Any?>(
            "objective" to query,
            "search_queries" to listOf(query),
            "session_id" to sessionId
        )

        val headers = mutableMapOf<String, String>()
        if (apiKey != null) {
            headers["Authorization"] = "Bearer $apiKey"
        }

        val requestBody = buildMcpRequest("web_search", arguments)
        return callMcp(PARALLEL_MCP_URL, requestBody, headers)
    }

    private suspend fun callMcp(url: String, body: Map<String, Any?>, extraHeaders: Map<String, String> = emptyMap()): String? {
        var spec = client.post()
            .uri(url)
            .accept(MediaType.APPLICATION_JSON, MediaType.TEXT_EVENT_STREAM)
            .contentType(MediaType.APPLICATION_JSON)
        for ((key, value) in extraHeaders) {
            spec = spec.header(key, value)
        }
        val response = spec
            .bodyValue(body)
            .retrieve()
            .bodyToMono<String>()
            .awaitSingle()

        return parseMcpResponse(response)
    }

    companion object {
        const val PARALLEL_MCP_URL = "https://search.parallel.ai/mcp"
    }
}

/**
 * Searches the web using a pluggable provider backend (Exa or Parallel via MCP protocol).
 *
 * Ported from OpenCode's `websearch.ts` + `mcp-websearch.ts`:
 * - JSON-RPC 2.0 MCP protocol for search provider calls
 * - Supports both JSON and SSE response parsing
 * - Provider selection via environment variables or configuration
 */
class WebSearchTool(
    metadata: ToolMetadata,
    private val providers: List<WebSearchProvider>,
    private val defaultProviderName: String = "exa"
) : BaseToolDefinition(metadata) {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val executionMode = ToolExecutionMode.PARALLEL
    override fun parameterType() = WebSearchParams::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        val query = args["url"] as? String ?: args["query"] as? String
        if (query.isNullOrBlank()) {
            return@withContext errorResult("Error: 'query' parameter is required")
        }

        val params = WebSearchParams(
            query = query,
            numResults = (args["numResults"] as? Number)?.toInt()?.coerceIn(1, MAX_NUM_RESULTS),
            livecrawl = args["livecrawl"] as? String,
            type = args["type"] as? String,
            contextMaxCharacters = (args["contextMaxCharacters"] as? Number)?.toInt()?.coerceIn(1, MAX_CONTEXT_CHARACTERS)
        )

        val provider = resolveProvider()
        if (provider == null) {
            return@withContext errorResult(
                "No search provider configured. Set EXA_API_KEY or PARALLEL_API_KEY environment variable."
            )
        }

        onUpdate(ToolUpdate.Progress("${provider.label}: searching \"${params.query}\""))

        try {
            val result = withTimeout(SEARCH_TIMEOUT.seconds) {
                provider.search(params.query, params, agentContext.sessionId ?: "default")
            }
            val output = result ?: NO_RESULTS
            ToolResult(
                content = listOf(TextContent(output)),
                details = mapOf("provider" to provider.providerName)
            )
        } catch (_: TimeoutCancellationException) {
            errorResult("Search timed out after ${SEARCH_TIMEOUT}s")
        } catch (e: Exception) {
            logger.warn("Web search failed for query '{}': {}", query, e.message)
            errorResult("Search failed: ${e.message}")
        }
    }

    private fun resolveProvider(): WebSearchProvider? {
        if (providers.isEmpty()) return null
        val preferred = System.getenv("EASYAI_WEBSEARCH_PROVIDER") ?: defaultProviderName
        return providers.find { it.providerName == preferred } ?: providers.first()
    }

    companion object {
        private const val SEARCH_TIMEOUT = 25
        private const val MAX_NUM_RESULTS = 20
        private const val MAX_CONTEXT_CHARACTERS = 50_000
        const val NO_RESULTS = "No search results found. Please try a different query."
    }
}

// ---- MCP protocol helpers ----

private val objectMapper = SharedObjectMapper.instance

/**
 * Builds a JSON-RPC 2.0 request body for MCP tool call.
 */
private fun buildMcpRequest(toolName: String, arguments: Map<String, Any?>): Map<String, Any?> {
    return mapOf(
        "jsonrpc" to "2.0",
        "id" to 1,
        "method" to "tools/call",
        "params" to mapOf(
            "name" to toolName,
            "arguments" to arguments
        )
    )
}

/**
 * Parses MCP response — supports both direct JSON and SSE format.
 * Ported from OpenCode's `mcp-websearch.ts` `parseResponse`.
 */
internal fun parseMcpResponse(body: String): String? {
    val trimmed = body.trim()
    if (trimmed.isEmpty()) return null

    // Try direct JSON parse first
    if (trimmed.startsWith("{")) {
        val direct = parseMcpPayload(trimmed)
        if (direct != null) return direct
    }

    // Try SSE format: "data: {...}" lines
    for (line in body.lines()) {
        if (!line.startsWith("data: ")) continue
        val payload = line.removePrefix("data: ").trim()
        val result = parseMcpPayload(payload)
        if (result != null) return result
    }
    return null
}

/**
 * Parses a single MCP JSON payload to extract the text content.
 * Expected format: { "result": { "content": [{ "type": "text", "text": "..." }] } }
 */
private fun parseMcpPayload(json: String): String? {
    return try {
        val tree = objectMapper.readTree(json)
        val contentArray = tree.get("result")?.get("content") ?: return null
        if (!contentArray.isArray) return null
        for (item in contentArray) {
            val text = item.get("text")?.asString()
            if (!text.isNullOrBlank()) return text
        }
        null
    } catch (_: Exception) {
        null
    }
}
