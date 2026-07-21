package com.easy.easyai.tools.mcp

import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import java.time.Duration

/**
 * Standalone test to verify MCP trading server connectivity and tool call latency.
 *
 * Run via:
 *   mvn -pl easyai-tools test -Dtest=McpToolCallTest -DfailIfNoTests=false
 *
 * Or just run the main() function directly from IDE.
 */
fun main() = runBlocking {
    val command = System.getenv("TRADING_MCP_PATH") ?: "trading-mcp"
    val timeoutSeconds = 120L

    println("=== MCP Trading Server Tool Call Test ===")
    println("Command: $command")
    println("Timeout: ${timeoutSeconds}s")
    println()

    // Step 1: Create transport (same as McpClientManager.createTransport)
    val params = ServerParameters.builder(command)
        .args(emptyList())
        .env(emptyMap())
        .build()
    val mapper = McpJsonDefaults.getMapper()
    val transport = StdioClientTransport(params, mapper)

    // Step 2: Create async client
    val client = McpClient.async(transport)
        .clientInfo(McpSchema.Implementation.builder("easyai-test", "1.0.0").build())
        .requestTimeout(Duration.ofSeconds(timeoutSeconds))
        .build()

    try {
        // Step 3: Initialize
        println("[1/4] Initializing MCP connection...")
        val startInit = System.currentTimeMillis()
        client.initialize().awaitSingle()
        val initTime = System.currentTimeMillis() - startInit
        println("  ✓ Initialized in ${initTime}ms")

        // Step 4: List tools
        println("[2/4] Listing tools...")
        val startList = System.currentTimeMillis()
        val tools = client.listTools().awaitSingle().tools() ?: emptyList()
        val listTime = System.currentTimeMillis() - startList
        println("  ✓ Found ${tools.size} tools in ${listTime}ms")

        // Test sequence: futu tools first (to stress-test stdout pollution), then yfinance
        val toolsToTest = listOf(
            mapOf("name" to "company_news", "args" to mapOf<String, Any>("symbol" to "09988.HK")),
            mapOf("name" to "stock_profile", "args" to mapOf<String, Any>("symbol" to "09988.HK")),
            mapOf("name" to "fund_flow", "args" to mapOf<String, Any>("symbol" to "09988.HK")),
            mapOf("name" to "financial_statements", "args" to mapOf<String, Any>(
                "symbol" to "BABA",
                "statement_type" to "balance",
                "period" to "annual",
                "limit" to 4
            ))
        )

        var stepNum = 3
        for (toolTest in toolsToTest) {
            val toolName = toolTest["name"] as String
            @Suppress("UNCHECKED_CAST")
            val toolArgs = toolTest["args"] as Map<String, Any>

            println("[$stepNum/${3 + toolsToTest.size - 1}] Calling $toolName with $toolArgs ...")
            val mcpTool = tools.find { it.name() == toolName || it.name() == toolName.replace("_", "-") }
            if (mcpTool == null) {
                println("  ✗ Tool '$toolName' NOT found!")
                stepNum++
                continue
            }
            val request = McpSchema.CallToolRequest.builder(mcpTool.name())
                .arguments(toolArgs)
                .build()

            val startCall = System.currentTimeMillis()
            try {
                val result = client.callTool(request).awaitSingle()
                val callTime = System.currentTimeMillis() - startCall
                println("  ✓ Tool call completed in ${callTime}ms")
                println("  isError: ${result.isError}")

                val textContent = result.content()
                    ?.filterIsInstance<McpSchema.TextContent>()
                    ?.joinToString("\n") { it.text() }
                    ?: "(empty)"
                val preview = if (textContent.length > 300) textContent.take(300) + "..." else textContent
                println("  Result preview:\n$preview")
            } catch (e: Exception) {
                val callTime = System.currentTimeMillis() - startCall
                println("  ✗ FAILED after ${callTime}ms: ${e.javaClass.simpleName}: ${e.message}")
            }
            println()
            stepNum++
        }

        // Step 6: Summary
        println()
        println("[4/4] Summary:")
        println("  Init:     ${initTime}ms")
        println("  List:     ${listTime}ms")
        println("  Total:    ${System.currentTimeMillis() - startInit}ms")

    } catch (e: Exception) {
        val elapsed = System.currentTimeMillis()
        println("  ✗ ERROR: ${e.javaClass.simpleName}: ${e.message}")
        e.printStackTrace()
    } finally {
        println()
        println("Closing client...")
        try { client.close() } catch (_: Exception) {}
        println("Done.")
    }
}
