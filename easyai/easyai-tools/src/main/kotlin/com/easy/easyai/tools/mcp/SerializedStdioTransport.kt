package com.easy.easyai.tools.mcp

import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.spec.McpSchema
import org.slf4j.LoggerFactory
import reactor.core.publisher.Mono

/**
 * Stdio transport with serialized outbound enqueues and fail-fast on a dead connection.
 *
 * The MCP Java SDK enqueues outbound messages on the caller's thread into
 * `Sinks.many().unicast().onBackpressureBuffer()`, which is a serialize-prone sink: a
 * second thread emitting concurrently gets `FAIL_NON_SERIALIZED`, and the SDK folds that
 * into `RuntimeException("Failed to enqueue message")` without distinguishing the causes.
 * Concurrent `sendMessage` calls are routine here — [com.easy.easyai.core.tool.ToolExecutionEngine]
 * runs a turn's tool calls in parallel, so two MCP tools on one stdio server race on the
 * same sink. Measured with 4 threads x 25k emissions: 87.7% failed, 0% failed once the
 * emitters were mutually exclusive. The lock serializes producers only; the SDK's dedicated
 * writer thread still interleaves in-flight requests, so response correlation over one
 * connection is intact.
 *
 * With producers serialized, a failed enqueue can only mean the sink was completed or
 * cancelled, i.e. the transport is already gone — the child process died, its pipe broke, or
 * a close raced an in-flight call. Those become [McpTransportTerminatedException] so callers
 * evict and reconnect rather than retrying a dead client forever.
 */
internal open class SerializedStdioTransport(
    params: ServerParameters,
    mapper: McpJsonMapper,
    private val serverName: String,
) : StdioClientTransport(params, mapper) {

    /** Set when this process closes the transport, to tell a restart apart from a crashed child. */
    @Volatile
    private var closingByUs = false

    private val sendLock = Any()

    override fun sendMessage(message: McpSchema.JSONRPCMessage): Mono<Void> =
        Mono.defer { synchronized(sendLock) { super.sendMessage(message) } }
            .onErrorMap { e ->
                if (e is RuntimeException && e.message == ENQUEUE_FAILED) {
                    logger.warn(
                        "MCP stdio transport '{}' stopped accepting messages (closedByUs={}); reconnecting is required",
                        serverName, closingByUs,
                    )
                    McpTransportTerminatedException(serverName, closingByUs)
                } else {
                    e
                }
            }

    override fun closeGracefully(): Mono<Void> {
        closingByUs = true
        return super.closeGracefully()
    }

    companion object {
        private val logger = LoggerFactory.getLogger(SerializedStdioTransport::class.java)
        private const val ENQUEUE_FAILED = "Failed to enqueue message"
    }
}
