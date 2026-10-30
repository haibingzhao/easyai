package com.easy.easyai.web.service

import com.easy.easyai.core.host.HostIdentity
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.SpeechRecognizer
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import java.util.concurrent.ConcurrentHashMap

/** Everything one socket needs, resolved before the first frame is accepted. */
data class AsrRealtimeStart(
    val ticket: AsrTicket,
    val settings: MediaProviderSettings,
    val parameters: Map<String, Any?>,
    val modelId: String,
    val capability: String
)

/**
 * Issues recognition sockets and owns their lifecycle.
 *
 * The transport decision is made here, once, by [AsrCapabilityResolver] — the browser only obeys
 * `availability`. A ticket is refused when the row does not route to the socket, because the
 * alternative (handing out a ticket and failing at upgrade) produces an error the console cannot
 * explain.
 *
 * Concurrency is a product decision, not a protocol limit: one live recognition per caller and a
 * small global cap. Slots are released in a `finally`, so a failed upgrade cannot leak one.
 */
@Service
class AsrRealtimeService(
    @param:Autowired(required = false)
    private val auxModelResolver: AuxModelResolver? = null,
    @param:Autowired(required = false)
    private val capabilityResolver: AsrCapabilityResolver? = null,
    private val tickets: AsrTicketStore,
    private val host: HostIdentity,
    @param:Autowired(required = false)
    private val recognizer: SpeechRecognizer? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    private val engine: SpeechRecognizer = recognizer ?: SpeechRecognizer.create()

    /** Callers with a socket open; the second concurrent round for one caller is refused. */
    private val activeSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Mint a handshake ticket for [owners].
     *
     * [origin] is the browser's `Origin` on the POST; it is stored and re-checked at upgrade, so a
     * ticket cannot be replayed from another site.
     */
    suspend fun issueTicket(owners: Collection<String>, origin: String?): AsrTicket {
        val config = auxModelResolver?.resolveConfig(owners, AuxModelTask.ASR)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "No speech-recognition model is configured")
        val route = resolver().resolve(config)
        if (route.transport != AsrTransport.WEBSOCKET) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The configured recognition model does not use the realtime channel (${route.reason ?: route.transport})"
            )
        }
        val userId = owners.firstOrNull()
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No owner in scope")
        val ticket = tickets.issue(
            userId = userId,
            appName = host.appName,
            owners = owners,
            origin = origin?.trim()?.takeIf { it.isNotEmpty() },
            configId = config.id,
            modelId = config.modelId
        ) ?: throw ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many pending recognition tickets")
        logger.info(
            "Issued ASR ticket for {} (recording {}, model {})",
            userId, ticket.recordingId, config.modelId
        )
        return ticket
    }

    /**
     * Claim the ticket the handshake filter accepted for this upgrade.
     *
     * Single use: the second call for the same value returns null, exactly like [AsrTicketStore.consume].
     */
    fun claimTicket(value: String): AsrTicket? = tickets.claim(value)

    /**
     * Resolve the session's configuration again.
     *
     * A ticket says *which* row was usable 30 seconds ago; it does not promise the row is still
     * enabled, complete, or pointed at the same model. Re-checking here means a saved edit between
     * issue and upgrade produces a clear refusal instead of a socket running the old model, and the
     * row id must still match — silently switching models would bill a model nobody selected.
     */
    suspend fun start(ticket: AsrTicket): AsrRealtimeStart {
        val config = auxModelResolver?.resolveConfig(ticket.owners, AuxModelTask.ASR)
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, "No speech-recognition model is configured")
        if (config.id != ticket.configId) {
            throw ResponseStatusException(HttpStatus.CONFLICT, "The recognition model changed since this ticket was issued")
        }
        val route = resolver().resolve(config)
        val usable = route.transport == AsrTransport.WEBSOCKET
        if (!usable) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The configured recognition model is no longer usable (${route.reason ?: route.transport})"
            )
        }
        val settings = AsrCapabilityResolver.asSettings(config)
        if (!engine.supports(settings)) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The configured recognition model uses a provider the realtime channel does not support"
            )
        }
        return AsrRealtimeStart(
            ticket = ticket,
            settings = settings,
            parameters = route.parameters,
            modelId = config.modelId,
            capability = route.capability
        )
    }

    /** Claim the caller's single recognition slot; false when one is already open. */
    fun acquire(owner: String): Boolean {
        if (activeSessions.size >= MAX_SESSIONS) return false
        return activeSessions.add(owner)
    }

    fun release(owner: String) {
        activeSessions.remove(owner)
    }

    fun recognize(start: AsrRealtimeStart, audio: Flux<ByteArray>) =
        engine.recognize(start.settings, start.parameters, audio)

    private fun resolver(): AsrCapabilityResolver = capabilityResolver ?: AsrCapabilityResolver()

    /** Maps a startup failure onto a stable reason the console can show. */
    fun reasonFor(error: Throwable): String = when (error) {
        is ResponseStatusException -> error.reason ?: "UNAVAILABLE"
        is CancellationException -> "CANCELLED"
        else -> "UNAVAILABLE"
    }

    companion object {
        const val MAX_SESSIONS = 32
    }
}
