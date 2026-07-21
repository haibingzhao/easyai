package com.easy.easyai.tools.web

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import java.time.Year

/**
 * Builder for [WebFetchTool].
 */
@Component
class WebFetchToolBuilder : ToolBuilder {
    override val metadata = ToolMetadata(
        name = "webfetch",
        description = """Fetches content from a specified URL.
- Takes a URL and optional format as input
- Fetches the URL content, converts to requested format (markdown by default)
- Format options: "markdown" (default), "text", or "html"
- This tool is read-only and does not modify any files
- Use this tool when you need to retrieve and analyze web content
- IMPORTANT: if another tool is present that offers better web fetching capabilities, prefer using that tool instead""",
        permissionCategory = "web",
        uiRenderer = "webfetch",
        patternKeys = listOf("url")
    )
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.web", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition {
        return WebFetchTool(metadata)
    }
}

/**
 * Builder for [WebSearchTool].
 *
 * Creates search providers based on environment variables:
 * - `EXA_API_KEY` — enables Exa search provider
 * - `PARALLEL_API_KEY` — enables Parallel search provider
 * - `EASYAI_WEBSEARCH_PROVIDER` — selects default provider ("exa" or "parallel")
 */
@Component
class WebSearchToolBuilder : ToolBuilder {

    private val logger = LoggerFactory.getLogger(javaClass)

    override val metadata = ToolMetadata(
        name = "websearch",
        description = """Search the web for real-time information.
- Provides up-to-date information for current events and recent data
- Use this tool for accessing information beyond knowledge cutoff
- Searches are performed automatically within a single API call
- Supports configurable result counts and live crawling modes
- The current year is ${Year.now()}. You MUST use this year when searching for recent information or current events""",
        permissionCategory = "web",
        uiRenderer = "websearch",
        patternKeys = listOf("query")
    )
    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.web", "*", PermissionAction.ALLOW)
    )

    override fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val exaApiKey = System.getenv("EXA_API_KEY") ?: System.getProperty("EXA_API_KEY")
        val parallelApiKey = System.getenv("PARALLEL_API_KEY")?: System.getProperty("PARALLEL_API_KEY")

        if (exaApiKey.isNullOrBlank() && parallelApiKey.isNullOrBlank()) {
            logger.debug("WebSearchTool disabled: neither EXA_API_KEY nor PARALLEL_API_KEY is set")
            return null
        }

        val client = WebClient.builder().build()
        val providers = mutableListOf<WebSearchProvider>()

        if (!exaApiKey.isNullOrBlank()) {
            providers.add(ExaSearchProvider(client, exaApiKey))
        }
        if (!parallelApiKey.isNullOrBlank()) {
            providers.add(ParallelSearchProvider(client, parallelApiKey))
        }

        val defaultProvider = System.getenv("EASYAI_WEBSEARCH_PROVIDER")
            ?: if (providers.any { it.providerName == "exa" }) "exa" else "parallel"

        logger.info("WebSearchTool initialized with providers: {}", providers.joinToString { it.providerName })
        return WebSearchTool(metadata, providers, defaultProvider)
    }
}
