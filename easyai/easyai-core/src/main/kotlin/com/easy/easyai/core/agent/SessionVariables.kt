package com.easy.easyai.core.agent

import java.util.Collections

/**
 * Thread-safe mutable session-scoped variable store.
 * Survives compaction (injected into system prompt, not message history).
 * Persisted to DB for cross-request recovery.
 *
 * Readers always observe a complete snapshot: writes rebuild the map under a lock and publish
 * it through a volatile reference, so a concurrent [getAll] can never see the intermediate state
 * of a compaction replacement (model variables present, trusted identity missing).
 */
class SessionVariables {

    private val lock = Any()

    @Volatile
    private var snapshot: Map<String, String> = emptyMap()

    /**
     * Keys owned by the server rather than by the conversation (e.g. a host's verified identity).
     * Compaction may rewrite or drop everything else, but it must never be able to launder these:
     * [replaceAll] re-applies them and [loadAll] refuses to overwrite them from persisted history.
     */
    private val trusted = LinkedHashMap<String, String>()

    /**
     * Conversation-owned write. A trusted key is server-owned and wins: writing it here is ignored
     * rather than overwriting a value [replaceAll] would restore on the next compaction.
     */
    fun put(key: String, value: String) {
        synchronized(lock) {
            if (trusted.containsKey(key)) return
            snapshot = snapshot + (key to value)
        }
    }

    /**
     * Put a value that [replaceAll] and [loadAll] must preserve.
     * Use only for values the server resolved itself, never for model- or user-authored data.
     */
    fun putTrusted(key: String, value: String) {
        synchronized(lock) {
            trusted[key] = value
            snapshot = snapshot + (key to value)
        }
    }

    /**
     * Drop a variable. This is also the only way to revoke a trusted key — without clearing the
     * trusted entry the next [replaceAll] would bring the value back. Server code only.
     */
    fun remove(key: String) {
        synchronized(lock) {
            trusted.remove(key)
            if (snapshot.containsKey(key)) snapshot = snapshot - key
        }
    }

    /** Read-only view of the current snapshot; callers cannot mutate the store's state. */
    fun getAll(): Map<String, String> = Collections.unmodifiableMap(snapshot)

    /**
     * The subset compaction may persist: everything not owned by the server.
     *
     * Trusted values are contributed afresh on every run entry. Persisting one would let a later
     * run whose contributor no longer supplies that key restore it from history as an ordinary,
     * mutable value — the exact cross-turn leak trusted values exist to prevent.
     */
    fun getConversationOwned(): Map<String, String> = synchronized(lock) {
        if (trusted.isEmpty()) return Collections.unmodifiableMap(snapshot)
        Collections.unmodifiableMap(snapshot.filterKeys { it !in trusted })
    }

    fun isEmpty(): Boolean = snapshot.isEmpty()

    fun size(): Int = snapshot.size

    /** Bulk load from DB restoration. Trusted keys are not overwritten by persisted values. */
    fun loadAll(data: Map<String, String>) {
        synchronized(lock) {
            var next = snapshot
            data.forEach { (key, value) -> if (!trusted.containsKey(key)) next = next + (key to value) }
            snapshot = next
        }
    }

    /**
     * Replace the conversation-owned variable set (used when compaction LLM outputs the
     * authoritative full state); trusted keys are re-applied on top and can never be dropped.
     */
    fun replaceAll(data: Map<String, String>) {
        synchronized(lock) {
            snapshot = data + trusted
        }
    }
}
