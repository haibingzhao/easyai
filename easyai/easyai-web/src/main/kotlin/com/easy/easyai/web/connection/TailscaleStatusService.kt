package com.easy.easyai.web.connection

import com.easy.easyai.common.util.ProcessExecutor
import com.easy.easyai.common.util.SharedObjectMapper
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import java.nio.file.Files
import java.nio.file.Paths
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * One tailnet node's link facts, as `tailscale status` reports them.
 *
 * `curAddr` is the endpoint in use; `peerRelay` is a self-hosted relay hop; `relayRegion` is the DERP
 * region the peer is homed at. A blank `curAddr` with a populated `peerRelay` is exactly what a
 * relayed path looks like.
 */
data class TailscalePeer(
    val nodeName: String,
    val tailscaleIps: List<String>,
    val addrs: List<String>,
    val curAddr: String,
    val relayRegion: String,
    val peerRelay: String,
    val online: Boolean,
)

data class TailscaleSnapshot(
    val available: Boolean,
    val selfName: String,
    val backendState: String,
    val peers: List<TailscalePeer>,
    val takenAtMillis: Long,
    /** Why the last refresh failed; empty when the daemon answered. Shown in the console so a
     *  missing witness is diagnosable from the badge instead of from server logs. */
    val error: String = "",
) {
    fun peerByAddress(address: String): TailscalePeer? = peers.firstOrNull { address in it.tailscaleIps }

    fun peerOwningAddress(hostPort: String): TailscalePeer? = peers.firstOrNull { peer ->
        peer.addrs.any { hostPart(it) == hostPart(hostPort) }
    }

    companion object {
        fun unavailable(takenAtMillis: Long, error: String = "") = TailscaleSnapshot(
            available = false,
            selfName = "",
            backendState = "",
            peers = emptyList(),
            takenAtMillis = takenAtMillis,
            error = error,
        )

        private fun hostPart(value: String): String {
            val trimmed = value.trim()
            if (trimmed.startsWith("[")) return trimmed.substringAfter("[").substringBefore("]")
            // Only a single colon separates host from port; more than one means a bare IPv6 literal.
            return if (trimmed.count { it == ':' } == 1) trimmed.substringBefore(":") else trimmed
        }
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TailscaleStatusJson(
    @JsonProperty("BackendState") val backendState: String? = null,
    @JsonProperty("Self") val self: TailscaleNodeJson? = null,
    @JsonProperty("Peer") val peers: Map<String, TailscaleNodeJson> = emptyMap(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
private data class TailscaleNodeJson(
    @JsonProperty("HostName") val hostName: String? = null,
    @JsonProperty("TailscaleIPs") val tailscaleIps: List<String> = emptyList(),
    @JsonProperty("Addrs") val addrs: List<String>? = null,
    @JsonProperty("CurAddr") val curAddr: String? = null,
    @JsonProperty("Relay") val relay: String? = null,
    @JsonProperty("PeerRelay") val peerRelay: String? = null,
    @JsonProperty("Online") val online: Boolean? = null,
)

/**
 * Cached `tailscale status --json`.
 *
 * The CLI never runs on the request path: a stale cache triggers a background refresh and the caller
 * gets the previous snapshot. Otherwise the console's latency badge would partly be measuring our own
 * diagnostics, which is the one thing a connection-quality readout must not do.
 */
@Service
class TailscaleStatusService(private val properties: ConnectionProperties) {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val refreshing = AtomicBoolean(false)

    @Volatile
    private var cached: TailscaleSnapshot = TailscaleSnapshot.unavailable(0)

    @Volatile
    private var consecutiveFailures = 0

    @Volatile
    private var resolvedBinary: String? = null

    /**
     * Where to exec tailscale from.
     *
     * A bare name is resolved through `PATH` first; when that fails, macOS is special — the App Store
     * build ships no CLI outside the GUI bundle, so `tailscale serve` can be running while `tailscale`
     * is unresolvable for every process. An absolute configured path always wins.
     */
    private fun binary(): String {
        resolvedBinary?.let { return it }
        val configured = properties.tailscaleBinary
        val resolved = when {
            configured.contains('/') -> configured
            else -> pathExecutable(configured)
                ?: KNOWN_BUNDLE_PATHS.firstOrNull { Files.isExecutable(Paths.get(it)) }
                ?: configured
        }
        resolvedBinary = resolved
        return resolved
    }

    private fun pathExecutable(name: String): String? {
        val separator = System.getProperty("path.separator") ?: ":"
        return System.getenv("PATH").orEmpty()
            .split(separator)
            .map { directory -> Paths.get(directory, name) }
            .firstOrNull { Files.isExecutable(it) }
            ?.toString()
    }

    init {
        // Warm the cache so the first badge reading already carries the witness instead of reporting
        // "inferred" for one polling interval. Hosts without a CLI pay one failed exec and then back off.
        refreshInBackground()
    }

    fun snapshot(): TailscaleSnapshot {
        if (isStale(cached)) refreshInBackground()
        return cached
    }

    /** At most one CLI call in flight; readers never wait for it. */
    private fun refreshInBackground() {
        if (!refreshing.compareAndSet(false, true)) return
        scope.launch {
            try {
                cached = read()
            } finally {
                refreshing.set(false)
            }
        }
    }

    /** A missing CLI backs off exponentially instead of being retried every reader. */
    private fun isStale(snapshot: TailscaleSnapshot): Boolean {
        val ttlSeconds = properties.statusCacheTtlSeconds * minOf(consecutiveFailures + 1, BACKOFF_CAP)
        return System.currentTimeMillis() - snapshot.takenAtMillis > ttlSeconds * 1000
    }

    private suspend fun read(): TailscaleSnapshot {
        val exe = binary()
        val result = try {
            ProcessExecutor.execute(
                command = listOf(exe, "status", "--json"),
                timeoutSeconds = properties.execTimeoutSeconds,
            )
        } catch (exception: Exception) {
            // No tailscale on this host (desktop app, CI, a container), or a PATH the service user
            // cannot see — report the reason instead of a bare "unavailable".
            logger.debug("{} status unavailable: {}", exe, exception.message)
            consecutiveFailures++
            return TailscaleSnapshot.unavailable(System.currentTimeMillis(), "exec failed ($exe): ${exception.message}")
        }

        if (result == null) {
            consecutiveFailures++
            return TailscaleSnapshot.unavailable(
                System.currentTimeMillis(),
                "timed out after ${properties.execTimeoutSeconds}s ($exe)",
            )
        }
        if (result.exitCode != 0) {
            consecutiveFailures++
            val detail = result.stderr.take(MAX_ERROR_CHARS)
            logger.debug("{} status exited {}: {}", exe, result.exitCode, detail)
            return TailscaleSnapshot.unavailable(System.currentTimeMillis(), "exit ${result.exitCode} ($exe): $detail")
        }

        return try {
            consecutiveFailures = 0
            parseTailscaleStatus(result.stdout)
        } catch (exception: Exception) {
            consecutiveFailures++
            logger.warn("Failed to parse {} status: {}", exe, exception.message)
            TailscaleSnapshot.unavailable(System.currentTimeMillis(), "parse failed: ${exception.message}")
        }
    }

    private companion object {
        const val BACKOFF_CAP = 12
        const val MAX_ERROR_CHARS = 200

        /** The macOS GUI build keeps its CLI inside the app bundle; Linux/brew installs differ. */
        val KNOWN_BUNDLE_PATHS = listOf(
            "/Applications/Tailscale.app/Contents/MacOS/Tailscale",
            "/usr/local/bin/tailscale",
            "/opt/homebrew/bin/tailscale",
        )
    }
}

/**
 * Maps `tailscale status --json` onto the fields the route rules need.
 *
 * The keys are Go's exported names (`Peer`, `CurAddr`, `PeerRelay`), and `Peer` is a map keyed by
 * node public key rather than a list — pinned down here against a captured fixture so a tailscale
 * upgrade that renames or reshapes them fails a test instead of quietly reporting `UNKNOWN`.
 */
internal fun parseTailscaleStatus(json: String): TailscaleSnapshot {
    val status = SharedObjectMapper.instance.readValue(json, TailscaleStatusJson::class.java)
    return TailscaleSnapshot(
        available = true,
        selfName = status.self?.hostName.orEmpty(),
        backendState = status.backendState.orEmpty(),
        peers = status.peers.values.map { node ->
            TailscalePeer(
                nodeName = node.hostName.orEmpty(),
                tailscaleIps = node.tailscaleIps,
                addrs = node.addrs.orEmpty(),
                curAddr = node.curAddr.orEmpty().trim(),
                relayRegion = node.relay.orEmpty().trim(),
                peerRelay = node.peerRelay.orEmpty().trim(),
                online = node.online == true,
            )
        },
        takenAtMillis = System.currentTimeMillis(),
    )
}
