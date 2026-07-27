package com.easy.easyai.core.agent

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe mutable session-scoped variable store.
 * Survives compaction (injected into system prompt, not message history).
 * Persisted to DB for cross-request recovery.
 */
class SessionVariables {

    private val vars = ConcurrentHashMap<String, String>()

    fun put(key: String, value: String) {
        vars[key] = value
    }

    fun remove(key: String) {
        vars.remove(key)
    }

    fun getAll(): Map<String, String> = vars.toMap()

    fun isEmpty(): Boolean = vars.isEmpty()

    fun size(): Int = vars.size

    /** Bulk load from DB restoration. */
    fun loadAll(data: Map<String, String>) {
        vars.putAll(data)
    }
}
