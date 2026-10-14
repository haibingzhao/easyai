package com.easy.easyai.web.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

private const val TS_HOST = "mac-mini.tailae6c41.ts.net"
private const val CLIENT_IP = "100.65.179.113"
private const val RELAY_REPORT = "47.121.24.5:40000:vni:23"

private fun serveSignals(
    origin: String? = CLIENT_IP,
    host: String = TS_HOST,
    xForwardedHost: String? = host,
    via: String? = null,
) = RequestSignals(
    host = host,
    scheme = "https",
    remoteAddress = "127.0.0.1",
    xForwardedFor = listOfNotNull(origin),
    xForwardedHost = xForwardedHost,
    via = via,
)

private fun peer(
    curAddr: String = "",
    peerRelay: String = "",
    relayRegion: String = "",
    name: String = "",
    addrs: List<String> = emptyList(),
    ips: List<String> = listOf(CLIENT_IP),
) = TailscalePeer(
    nodeName = name,
    tailscaleIps = ips,
    addrs = addrs,
    curAddr = curAddr,
    relayRegion = relayRegion,
    peerRelay = peerRelay,
    online = true,
)

private fun snapshot(vararg peers: TailscalePeer) = TailscaleSnapshot(
    available = true,
    selfName = "mac-mini",
    backendState = "Running",
    peers = peers.toList(),
    takenAtMillis = System.currentTimeMillis(),
)

private val emptySnapshot = TailscaleSnapshot.unavailable(0)
private val relayLabels = mapOf("47.121.24.5" to "aliyun-relay")

class ConnectionRouteResolverTest {

    @Nested
    inner class TailnetTransport {

        @Test
        fun `reads a punched endpoint as a direct tailnet path`() {
            val decision = ConnectionRouteResolver.resolve(
                serveSignals(),
                snapshot(peer(curAddr = "192.168.113.134:41641", relayRegion = "hkg")),
                relayLabels,
            )
            assertEquals(ConnectionRoute.TAILNET_DIRECT, decision.route)
            assertNull(decision.relay)
        }

        @Test
        fun `names a peer relay hop from configuration when the relay node reports no addresses`() {
            val decision = ConnectionRouteResolver.resolve(
                serveSignals(),
                snapshot(peer(peerRelay = RELAY_REPORT, relayRegion = "hkg")),
                relayLabels,
            )
            assertEquals(ConnectionRoute.RELAY_PEER, decision.route)
            assertEquals(RelayHop(RelayHopKind.PEER, "aliyun-relay", "47.121.24.5:40000", null), decision.relay)
        }

        @Test
        fun `reverse-looks-up an unconfigured relay hop from its own peer entry`() {
            val decision = ConnectionRouteResolver.resolve(
                serveSignals(),
                snapshot(
                    peer(peerRelay = RELAY_REPORT),
                    peer(name = "relay-2", ips = listOf("100.94.195.93"), addrs = listOf("47.121.24.5:40000")),
                ),
                emptyMap(),
            )
            assertEquals("relay-2", decision.relay?.name)
        }

        @Test
        fun `falls back to the derp region when no endpoint and no relay node are in use`() {
            val decision = ConnectionRouteResolver.resolve(serveSignals(), snapshot(peer(relayRegion = "sfo")), relayLabels)
            assertEquals(ConnectionRoute.RELAY_DERP, decision.route)
            assertEquals(RelayHop(RelayHopKind.DERP, null, null, "sfo"), decision.relay)
        }

        @Test
        fun `stays unknown when the peer reports nothing to go on`() {
            val decision = ConnectionRouteResolver.resolve(serveSignals(), snapshot(peer()), relayLabels)
            assertEquals(ConnectionRoute.UNKNOWN, decision.route)
            assertEquals(CLIENT_IP, decision.peerAddress)
        }

        @Test
        fun `stays unknown without a tailscale daemon while keeping the observed origin`() {
            val decision = ConnectionRouteResolver.resolve(serveSignals(), emptySnapshot, relayLabels)
            assertEquals(ConnectionRoute.UNKNOWN, decision.route)
            assertEquals(CLIENT_IP, decision.peerAddress)
        }
    }

    @Nested
    inner class ForwardedHeaders {

        @Test
        fun `does not mistake the identity headers serve itself injects for an http proxy`() {
            val decision = ConnectionRouteResolver.resolve(
                serveSignals(xForwardedHost = TS_HOST),
                snapshot(peer(curAddr = "100.65.179.113:41641")),
                relayLabels,
            )
            assertEquals(ConnectionRoute.TAILNET_DIRECT, decision.route)
        }

        @Test
        fun `reads a differing forwarded host as one proxy hop`() {
            val decision = ConnectionRouteResolver.resolve(
                serveSignals(xForwardedHost = "easyai.example.com", origin = "203.0.113.9"),
                emptySnapshot,
                relayLabels,
            )
            assertEquals(ConnectionRoute.HTTP_PROXY, decision.route)
            assertEquals("easyai.example.com", decision.relay?.name)
        }

        @Test
        fun `reads a via header as one proxy hop`() {
            val decision = ConnectionRouteResolver.resolve(serveSignals(via = "1.1 nginx"), emptySnapshot, relayLabels)
            assertEquals(ConnectionRoute.HTTP_PROXY, decision.route)
        }

        @Test
        fun `attributes a proxied request to the hop the tailnet can describe`() {
            val signals = RequestSignals(
                host = TS_HOST,
                scheme = "https",
                remoteAddress = "127.0.0.1",
                xForwardedFor = listOf("203.0.113.9", CLIENT_IP),
                xForwardedHost = null,
                via = null,
            )
            val decision = ConnectionRouteResolver.resolve(signals, snapshot(peer(curAddr = "1.2.3.4:5678")), relayLabels)
            assertEquals(ConnectionRoute.TAILNET_DIRECT, decision.route)
            assertEquals(CLIENT_IP, decision.peerAddress)
        }

        @Test
        fun `falls back to the client address when no hop resolves to a known peer`() {
            val signals = RequestSignals(
                host = TS_HOST,
                scheme = "https",
                remoteAddress = "127.0.0.1",
                xForwardedFor = listOf("203.0.113.9", "100.78.91.50"),
                xForwardedHost = null,
                via = null,
            )
            val decision = ConnectionRouteResolver.resolve(signals, snapshot(peer(ips = listOf("100.111.222.33"))), relayLabels)
            assertEquals(ConnectionRoute.UNKNOWN, decision.route)
            assertEquals("203.0.113.9", decision.peerAddress)
        }
    }

    @Nested
    inner class OtherChannels {

        @Test
        fun `reads loopback as the desktop app talking to its own backend`() {
            val signals = serveSignals(origin = null)
            assertEquals(ConnectionRoute.LOCAL, ConnectionRouteResolver.resolve(signals, emptySnapshot, relayLabels).route)
        }

        @Test
        fun `reads a private address as the same room`() {
            val signals = serveSignals(origin = "192.168.113.42")
            assertEquals(ConnectionRoute.LAN, ConnectionRouteResolver.resolve(signals, emptySnapshot, relayLabels).route)
        }

        @Test
        fun `leaves an unmatched public origin unknown rather than guessing`() {
            val signals = serveSignals(origin = "203.0.113.9")
            assertEquals(ConnectionRoute.UNKNOWN, ConnectionRouteResolver.resolve(signals, emptySnapshot, relayLabels).route)
        }

        @Test
        fun `keeps addresses above the tailscale allocation block out of tailnet handling`() {
            val signals = serveSignals(origin = "100.128.0.1")
            val decision = ConnectionRouteResolver.resolve(signals, snapshot(peer(ips = listOf("100.128.0.1"))), relayLabels)
            assertEquals(ConnectionRoute.UNKNOWN, decision.route)
        }
    }
}
