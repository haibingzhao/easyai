package com.easy.easyai.web.connection

import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Runs the real `tailscale status --json` through the same path the service uses.
 *
 * The fixture test pins the field names a captured report gave; this one catches what a fixture
 * written by hand cannot — a key that only appears on a long-lived node, or a value shape tailscale
 * emits but nobody expected. Skipped where no CLI exists (CI, containers), and it asserts only the
 * parse survived, because which path a developer's own tailnet takes right now is not a fact to fix.
 */
class TailscaleStatusLiveTest {

    @Test
    fun `parses the local tailscale report into a usable snapshot`() {
        assumeTrue(cliPresent(), "no tailscale CLI on this host")

        val snapshot = parseTailscaleStatus(runStatus())

        assertTrue(snapshot.available)
        assertTrue(snapshot.peers.isNotEmpty(), "tailscale reported no peers to parse")
        // `Self.HostName` is the one field every tailscale version has populated for a running node.
        assertTrue(snapshot.selfName.isNotBlank())

        val relayed = snapshot.peers.firstOrNull { it.peerRelay.isNotBlank() }
        if (relayed != null) {
            // A vni-suffixed report must still yield a host:port the label lookup can key on.
            val hostPort = relayed.peerRelay.substringBefore(":vni:")
            assertTrue(hostPort.contains(':'), "unexpected relay report: ${relayed.peerRelay}")
        }
    }

    private fun cliPresent(): Boolean {
        val separator = System.getProperty("path.separator") ?: ":"
        return System.getenv("PATH").orEmpty()
            .split(separator)
            .any { directory -> Files.isExecutable(Paths.get(directory, "tailscale")) }
    }

    private fun runStatus(): String {
        val process = ProcessBuilder("tailscale", "status", "--json").start()
        // Drain the pipe before waiting: a large tailnet's report can outgrow the OS pipe buffer.
        val output = process.inputStream.bufferedReader().readText()
        assumeTrue(process.waitFor(5, TimeUnit.SECONDS), "tailscale status did not finish in 5s")
        assertEquals(0, process.exitValue())
        return output
    }
}
