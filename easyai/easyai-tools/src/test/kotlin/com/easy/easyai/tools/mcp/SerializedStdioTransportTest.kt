package com.easy.easyai.tools.mcp

import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.json.McpJsonDefaults
import io.modelcontextprotocol.spec.McpSchema
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SerializedStdioTransportTest {

    private fun newTransport(): SerializedStdioTransport = SerializedStdioTransport(
        params = ServerParameters.builder("echo").args(emptyList()).env(emptyMap()).build(),
        mapper = McpJsonDefaults.getMapper(),
        serverName = "probe-server",
    )

    private fun request(id: Int): McpSchema.JSONRPCMessage =
        McpSchema.JSONRPCRequest("probe", id)

    @Nested
    inner class ConcurrentEnqueue {

        @Test
        fun `never rejects a message when several threads send at once`() {
            val transport = newTransport()
            val failures = AtomicInteger()
            val threads = 8
            val perThread = 200
            val ready = CountDownLatch(threads)
            val start = CountDownLatch(1)
            val done = CountDownLatch(threads)

            repeat(threads) { index ->
                val worker = Thread {
                    val message = request(index)
                    try {
                        ready.countDown()
                        start.await()
                        repeat(perThread) {
                            transport.sendMessage(message).subscribe({}, { failures.incrementAndGet() })
                        }
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                    } finally {
                        done.countDown()
                    }
                }
                worker.isDaemon = true
                worker.start()
            }

            assertTrue(ready.await(10, TimeUnit.SECONDS), "all senders must be queued up")
            start.countDown()
            assertTrue(done.await(60, TimeUnit.SECONDS), "senders must finish")
            transport.closeGracefully().block(Duration.ofSeconds(5))

            assertEquals(0, failures.get(), "serialized producers must never fail to enqueue")
        }
    }

    @Nested
    inner class TerminatedTransport {

        @Test
        fun `reports a closed connection as requiring a reconnect`() {
            val transport = newTransport()
            transport.closeGracefully().block(Duration.ofSeconds(5))

            val error = assertThrows(McpTransportTerminatedException::class.java) {
                transport.sendMessage(request(1)).block(Duration.ofSeconds(5))
            }
            assertTrue(error.closedByUs, "this close was initiated by us, not by a dying child process")
            assertTrue(error.message!!.contains("probe-server"))
        }
    }
}
