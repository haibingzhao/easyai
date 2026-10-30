package com.easy.easyai.web.service

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * One issued handshake credential.
 *
 * The browser cannot set an `Authorization` header on a native `WebSocket`, and a long-lived JWT in
 * the query string would end up in proxy access logs and browser history. A ticket is therefore the
 * smallest thing that can carry identity across that one hop: 256 random bits, single use, 30
 * seconds, and bound to the request that asked for it so a stolen value cannot be replayed from
 * another origin or against another model.
 */
data class AsrTicket(
    val value: String,
    val userId: String,
    val appName: String,
    /** Self → group owner set captured at issue time; a session never re-derives it. */
    val owners: List<String>,
    val origin: String?,
    val recordingId: String,
    val configId: String,
    val modelId: String,
    val expiresAt: Long
)

/**
 * In-memory store of unconsumed tickets.
 *
 * Deliberately not durable and not shared: the design is single-instance, and a ticket that
 * survives a restart or hops between replicas would be a much larger security surface than the one
 * it replaces. `ConcurrentHashMap.remove` is the atomic consume — exactly one caller can win, so a
 * replay cannot open a second socket.
 *
 * Expired tickets never hold an active-session slot: they are pruned on every issue and consume,
 * and the per-user / global caps count only live ones.
 */
@Service
class AsrTicketStore {

    private val logger = LoggerFactory.getLogger(javaClass)
    private val tickets = ConcurrentHashMap<String, AsrTicket>()
    private val perUser = ConcurrentHashMap<String, AtomicInteger>()

    /**
     * Tickets the handshake filter accepted and parked for the socket handler.
     *
     * The filter authenticates the upgrade, but `HandshakeInfo.attributes` only carries *web-session*
     * attributes (empty unless a `sessionAttributePredicate` is configured), so an exchange attribute
     * cannot reach the handler. This is the one-hop handoff instead: [consume] already guaranteed a
     * single winner, and [claim] removes the entry, so one value opens exactly one socket.
     */
    private val accepted = ConcurrentHashMap<String, AsrTicket>()
    private val random = SecureRandom()

    fun issue(
        userId: String,
        appName: String,
        owners: Collection<String>,
        origin: String?,
        configId: String,
        modelId: String
    ): AsrTicket? = issue(userId, appName, owners, origin, configId, modelId, TTL_MILLIS)

    /** TTL is a parameter so expiry can be exercised without waiting 30 seconds. */
    internal fun issue(
        userId: String,
        appName: String,
        owners: Collection<String>,
        origin: String?,
        configId: String,
        modelId: String,
        ttlMillis: Long
    ): AsrTicket? {
        pruneExpired()
        val live = perUser[userId]?.get() ?: 0
        if (live >= MAX_PER_USER) {
            logger.info("ASR ticket refused for {}: {} tickets already pending", userId, live)
            return null
        }
        if (tickets.size >= MAX_TOTAL) {
            logger.warn("ASR ticket refused: {} tickets pending", tickets.size)
            return null
        }
        val ticket = AsrTicket(
            value = newValue(),
            userId = userId,
            appName = appName,
            owners = owners.toList(),
            origin = origin,
            recordingId = UUID.randomUUID().toString(),
            configId = configId,
            modelId = modelId,
            expiresAt = System.currentTimeMillis() + ttlMillis
        )
        tickets[ticket.value] = ticket
        perUser.computeIfAbsent(userId) { AtomicInteger() }.incrementAndGet()
        return ticket
    }

    /** Atomic single-use consumption; null when unknown, already used, or expired. */
    fun consume(value: String): AsrTicket? {
        pruneExpired()
        val ticket = tickets.remove(value) ?: return null
        perUser[ticket.userId]?.decrementAndGet()
        if (ticket.expiresAt <= System.currentTimeMillis()) {
            logger.info("ASR ticket for {} arrived after it expired", ticket.userId)
            return null
        }
        return ticket
    }

    fun pendingCount(): Int {
        pruneExpired()
        return tickets.size
    }

    /** Park an accepted ticket so the socket handler can pick it up; see [accepted]. */
    fun handOver(ticket: AsrTicket) {
        accepted[ticket.value] = ticket
    }

    /** Take the parked ticket; null when it was already claimed or never handed over. */
    fun claim(value: String): AsrTicket? = accepted.remove(value)

    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        for ((value, ticket) in tickets) {
            if (ticket.expiresAt > now) continue
            if (tickets.remove(value, ticket)) perUser[ticket.userId]?.decrementAndGet()
        }
        // A handoff whose upgrade never completed would otherwise outlive its ticket.
        for ((value, ticket) in accepted) {
            if (ticket.expiresAt > now) continue
            accepted.remove(value, ticket)
        }
    }

    private fun newValue(): String {
        val bytes = ByteArray(TICKET_BYTES)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    companion object {
        /** 32 bytes is 256 bits, which is what the design asks a ticket to carry. */
        private const val TICKET_BYTES = 32
        const val TTL_SECONDS = 30L
        private const val TTL_MILLIS = TTL_SECONDS * 1000
        const val MAX_PER_USER = 2
        const val MAX_TOTAL = 1024
    }
}
