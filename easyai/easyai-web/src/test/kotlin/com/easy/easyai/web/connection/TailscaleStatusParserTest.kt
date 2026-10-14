package com.easy.easyai.web.connection

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Wire-format guard for `tailscale status --json` (captured shape, synthetic identities): the keys are
 * Go's exported names and `Peer` is a map keyed by node public key, so a reshape by a tailscale
 * upgrade must fail here rather than report `UNKNOWN` in production.
 */
class TailscaleStatusParserTest {

    private val statusJson = """
        {
          "Version": "1.104.1-t7f4efe814-g4939ea2e4",
          "BackendState": "Running",
          "TailscaleIPs": ["100.78.91.50"],
          "Self": {
            "ID": "nSYNTH01CNTRL",
            "HostName": "mac-mini",
            "DNSName": "mac-mini.tailae6c41.ts.net.",
            "TailscaleIPs": ["100.78.91.50"],
            "Addrs": ["192.168.113.10:41641"],
            "CurAddr": "",
            "Relay": "hkg",
            "Online": true
          },
          "Peer": {
            "nodekey:aaaabbbbccccddddeeeeffff00001111222233334444555566667777888899": {
              "ID": "nSYNTH02CNTRL",
              "HostName": "laptop",
              "DNSName": "laptop.tailae6c41.ts.net.",
              "TailscaleIPs": ["100.65.179.113", "fd7a:115c:a1e0::5330:b372"],
              "Addrs": null,
              "CurAddr": "",
              "Relay": "hkg",
              "PeerRelay": "47.121.24.5:40000:vni:23",
              "Online": true,
              "LastHandshake": "2026-10-14T06:53:00.000000Z"
            },
            "nodekey:bbbbccccddddeeeeffff0000111122223333444455556666777788889999aa": {
              "ID": "nSYNTH03CNTRL",
              "HostName": "aliyun-relay",
              "DNSName": "aliyun-relay.tailae6c41.ts.net.",
              "TailscaleIPs": ["100.94.195.93"],
              "Addrs": ["47.121.24.5:40000"],
              "CurAddr": "47.121.24.5:40000",
              "Relay": "sfo",
              "Online": true
            }
          }
        }
    """.trimIndent()

    @Test
    fun `maps the exported go field names and the peer-keyed map`() {
        val snapshot = parseTailscaleStatus(statusJson)

        assertTrue(snapshot.available)
        assertEquals("Running", snapshot.backendState)
        assertEquals("mac-mini", snapshot.selfName)
        assertEquals(2, snapshot.peers.size)

        val laptop = snapshot.peerByAddress("100.65.179.113")
        assertEquals("laptop", laptop?.nodeName)
        assertEquals("", laptop?.curAddr)
        assertEquals("hkg", laptop?.relayRegion)
        assertEquals("47.121.24.5:40000:vni:23", laptop?.peerRelay)
        assertTrue(laptop?.online == true)
        // `Addrs: null` for a relayed peer must land as an empty list, not an NPE.
        assertEquals(emptyList<String>(), laptop?.addrs)
    }

    @Test
    fun `resolves a relay hop address back to the node that owns it`() {
        val snapshot = parseTailscaleStatus(statusJson)
        assertEquals("aliyun-relay", snapshot.peerOwningAddress("47.121.24.5:40000")?.nodeName)
        assertNull(snapshot.peerOwningAddress("10.0.0.1:40000"))
    }

    @Test
    fun `reads the captured shape end to end as a relayed tailnet request`() {
        val snapshot = parseTailscaleStatus(statusJson)
        val signals = RequestSignals(
            host = "mac-mini.tailae6c41.ts.net",
            scheme = "https",
            remoteAddress = "127.0.0.1",
            xForwardedFor = listOf("100.65.179.113"),
            xForwardedHost = "mac-mini.tailae6c41.ts.net",
            via = null,
        )

        val decision = ConnectionRouteResolver.resolve(signals, snapshot, emptyMap())

        assertEquals(ConnectionRoute.RELAY_PEER, decision.route)
        assertEquals("47.121.24.5:40000", decision.relay?.address)
        assertEquals("aliyun-relay", decision.relay?.name)
        assertEquals("100.65.179.113", decision.peerAddress)
    }
}
