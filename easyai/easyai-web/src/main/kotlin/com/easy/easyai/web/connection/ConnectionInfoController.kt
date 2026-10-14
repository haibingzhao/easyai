package com.easy.easyai.web.connection

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpHeaders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ConnectionInfoDto(
    val observed: ObservedRequestDto,
    val route: ConnectionRoute,
    val relay: RelayHopDto?,
    /** Only the requesting node's own link facts — never its name, and never another peer. */
    val peer: RequestingPeerDto?,
    val server: ServerNodeDto,
    val generatedAt: Long,
)

data class ObservedRequestDto(
    val host: String,
    val scheme: String,
    val remoteAddress: String?,
    val xForwardedFor: List<String>,
    val xForwardedHost: String?,
    val via: String?,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class RelayHopDto(
    val kind: RelayHopKind,
    val name: String?,
    val address: String?,
    val derpRegion: String?,
)

data class RequestingPeerDto(
    val address: String?,
    val curAddr: String,
    val peerRelay: String,
    val relay: String,
    val online: Boolean,
)

data class ServerNodeDto(
    val hostname: String,
    val tailscaleAvailable: Boolean,
    val backendState: String,
    /** Why the tailscale witness is missing, when it is: the badge shows it instead of a bare dot. */
    val tailscaleError: String?,
)

/**
 * `GET /api/connection/info` — how this request reached the server.
 *
 * Public by design (see `SecurityConfig`): every field describes the caller's own request — its
 * forwarded headers, and its own peer entry in the tailnet — and never another device's identity.
 * When the adapter is switched off the route 404s, and the console keeps measuring latency against
 * `/api/chat/health` while labelling the route as inferred.
 */
@RestController
@RequestMapping("/api/connection")
@ConditionalOnProperty(prefix = "easyai.connection", name = ["enabled"], havingValue = "true", matchIfMissing = true)
class ConnectionInfoController(
    private val tailscaleStatusService: TailscaleStatusService,
    private val properties: ConnectionProperties,
) {

    @GetMapping("/info")
    fun info(exchange: ServerWebExchange): Mono<ConnectionInfoDto> {
        val request = exchange.request
        val headers = request.headers
        val forwardedProto = headers.getFirst("X-Forwarded-Proto")?.substringBefore(",")?.trim()
        val signals = RequestSignals(
            host = headers.getFirst(HttpHeaders.HOST).orEmpty(),
            scheme = forwardedProto?.takeIf { it.isNotBlank() } ?: request.uri.scheme.orEmpty(),
            remoteAddress = request.remoteAddress?.address?.hostAddress,
            xForwardedFor = splitForwardedChain(headers.getFirst("X-Forwarded-For")),
            xForwardedHost = headers.getFirst("X-Forwarded-Host")?.substringBefore(",")?.trim(),
            via = headers.getFirst(HttpHeaders.VIA)?.substringBefore(",")?.trim(),
        )

        val snapshot = tailscaleStatusService.snapshot()
        val decision = ConnectionRouteResolver.resolve(signals, snapshot, properties.relayLabels)
        val peer = decision.peerAddress?.let { snapshot.peerByAddress(it) }

        return Mono.just(
            ConnectionInfoDto(
                observed = ObservedRequestDto(
                    host = signals.host,
                    scheme = signals.scheme,
                    remoteAddress = signals.remoteAddress,
                    xForwardedFor = signals.xForwardedFor,
                    xForwardedHost = signals.xForwardedHost,
                    via = signals.via,
                ),
                route = decision.route,
                relay = decision.relay?.let { RelayHopDto(it.kind, it.name, it.address, it.derpRegion) },
                peer = peer?.let {
                    RequestingPeerDto(
                        address = decision.peerAddress,
                        curAddr = it.curAddr,
                        peerRelay = it.peerRelay,
                        relay = it.relayRegion,
                        online = it.online,
                    )
                },
                server = ServerNodeDto(
                    hostname = snapshot.selfName,
                    tailscaleAvailable = snapshot.available,
                    backendState = snapshot.backendState,
                    tailscaleError = snapshot.error.takeIf { it.isNotBlank() },
                ),
                generatedAt = System.currentTimeMillis(),
            )
        )
    }

    private fun splitForwardedChain(raw: String?): List<String> =
        raw?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
}
