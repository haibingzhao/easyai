package com.easy.easyai.web.connection

/** Where the request came from, as observed at the server socket and its forwarded headers. */
enum class ConnectionRoute {
    /** Loopback: the desktop app's own backend, or a developer on the same machine. */
    LOCAL,

    /** A private-address client: same room, no tunnel involved. */
    LAN,

    /** Tailnet peer reached over a punched endpoint. */
    TAILNET_DIRECT,

    /** Tailnet peer reached through a self-hosted relay node. */
    RELAY_PEER,

    /** Tailnet peer homed at — and reached through — an official DERP region. */
    RELAY_DERP,

    /** An HTTP reverse proxy hop in front of this server. */
    HTTP_PROXY,

    UNKNOWN,
}

enum class RelayHopKind { PEER, DERP, HTTP }

data class RelayHop(
    val kind: RelayHopKind,
    val name: String?,
    val address: String?,
    val derpRegion: String?,
)

data class RequestSignals(
    val host: String,
    val scheme: String,
    /** Address the socket was accepted from — loopback behind `tailscale serve`. */
    val remoteAddress: String?,
    val xForwardedFor: List<String>,
    val xForwardedHost: String?,
    val via: String?,
)

data class RouteDecision(
    val route: ConnectionRoute,
    val relay: RelayHop?,
    val peerAddress: String?,
)

/**
 * Turns request signals plus a tailnet snapshot into one route reading.
 *
 * Pure so the rules are testable without a socket: forwarded headers decide whether an HTTP proxy
 * stood in front, the origin address decides the transport, and the peer's own fields decide *which*
 * relay — in that order, because a peer homed at a DERP region is not the same claim as a peer
 * reached through one.
 */
object ConnectionRouteResolver {

    fun resolve(
        signals: RequestSignals,
        snapshot: TailscaleSnapshot,
        relayLabels: Map<String, String>,
    ): RouteDecision {
        val origin = selectOrigin(signals, snapshot)
        if (isHttpProxyHop(signals)) {
            return RouteDecision(
                route = ConnectionRoute.HTTP_PROXY,
                relay = RelayHop(
                    kind = RelayHopKind.HTTP,
                    name = signals.xForwardedHost?.takeIf { it.isNotBlank() } ?: signals.via,
                    address = null,
                    derpRegion = null,
                ),
                peerAddress = origin,
            )
        }
        if (origin.isNullOrBlank()) return RouteDecision(ConnectionRoute.UNKNOWN, null, null)
        if (isLoopback(origin)) return RouteDecision(ConnectionRoute.LOCAL, null, origin)

        if (isTailnet(origin)) {
            val peer = snapshot.peerByAddress(origin)
                ?: return RouteDecision(ConnectionRoute.UNKNOWN, null, origin)
            return decideFromPeer(peer, origin, snapshot, relayLabels)
        }

        if (isPrivate(origin)) return RouteDecision(ConnectionRoute.LAN, null, origin)
        return RouteDecision(ConnectionRoute.UNKNOWN, null, origin)
    }

    /**
     * The hop the snapshot can actually describe.
     *
     * A forwarded chain is ordered client-first, so with an HTTP proxy in front the leftmost entry is
     * a public address the tailnet knows nothing about, while `tailscale serve` appends the peer IP as
     * the last hop. Prefer the entry that resolves to a known peer, then fall back to the left of the
     * chain — which is the client's own address when nothing stood in front.
     */
    private fun selectOrigin(signals: RequestSignals, snapshot: TailscaleSnapshot): String? {
        val chain = signals.xForwardedFor.ifEmpty { listOfNotNull(signals.remoteAddress?.takeIf { it.isNotBlank() }) }
        return chain.firstOrNull { isTailnet(it) && snapshot.peerByAddress(it) != null } ?: chain.firstOrNull()
    }

    private fun decideFromPeer(
        peer: TailscalePeer,
        origin: String,
        snapshot: TailscaleSnapshot,
        relayLabels: Map<String, String>,
    ): RouteDecision = when {
        // An endpoint in use means WireGuard is carrying the session directly.
        peer.curAddr.isNotBlank() -> RouteDecision(ConnectionRoute.TAILNET_DIRECT, null, origin)

        peer.peerRelay.isNotBlank() -> {
            val hostPort = hostPortOf(peer.peerRelay)
            val host = hostPart(hostPort)
            RouteDecision(
                route = ConnectionRoute.RELAY_PEER,
                relay = RelayHop(
                    kind = RelayHopKind.PEER,
                    name = relayLabels[host] ?: snapshot.peerOwningAddress(hostPort)?.nodeName?.takeIf { it.isNotBlank() },
                    address = hostPort,
                    derpRegion = null,
                ),
                peerAddress = origin,
            )
        }

        // No endpoint and no relay node: the only path left is the DERP region the peer is homed at.
        peer.relayRegion.isNotBlank() -> RouteDecision(
            ConnectionRoute.RELAY_DERP,
            RelayHop(RelayHopKind.DERP, null, null, peer.relayRegion),
            origin,
        )

        else -> RouteDecision(ConnectionRoute.UNKNOWN, null, origin)
    }

    private fun isHttpProxyHop(signals: RequestSignals): Boolean {
        if (!signals.via.isNullOrBlank()) return true
        val forwardedHost = signals.xForwardedHost?.takeIf { it.isNotBlank() } ?: return false
        return hostPart(forwardedHost) != hostPart(signals.host)
    }

    /** `47.121.24.5:40000:vni:23` → `47.121.24.5:40000`. */
    private fun hostPortOf(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("[")) {
            val close = trimmed.indexOf(']')
            val host = trimmed.substring(0, close + 1)
            val remainder = trimmed.substring(close + 1).trimStart(':')
            val port = remainder.substringBefore(":").takeIf { it.all(Char::isDigit) }
            return if (port == null) host else "$host:$port"
        }
        val segments = trimmed.split(":")
        val port = segments.getOrNull(1)?.takeIf { it.all(Char::isDigit) }
        return if (port == null) trimmed else "${segments[0]}:$port"
    }

    private fun hostPart(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("[")) return trimmed.substringAfter("[").substringBefore("]")
        return if (trimmed.count { it == ':' } == 1) trimmed.substringBefore(":") else trimmed
    }

    private fun isLoopback(address: String): Boolean =
        address == "localhost" || address == "::1" || address == "0:0:0:0:0:0:0:1" || address.startsWith("127.")

    private fun isTailnet(address: String): Boolean {
        if (address.startsWith("fd7a:115c:a1e0")) return true
        if (!address.startsWith("100.")) return false
        val second = address.substringAfter("100.").substringBefore(".").toIntOrNull() ?: return false
        // Tailscale allocates from the 100.64.0.0/10 carrier-grade NAT block.
        return second in 64..127
    }

    private fun isPrivate(address: String): Boolean {
        if (address.endsWith(".local")) return true
        if (address.startsWith("10.") || address.startsWith("192.168.")) return true
        if (!address.startsWith("172.")) return false
        val second = address.substringAfter("172.").substringBefore(".").toIntOrNull() ?: return false
        return second in 16..31
    }
}
