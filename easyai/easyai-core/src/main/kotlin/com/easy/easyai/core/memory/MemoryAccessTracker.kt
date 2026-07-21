package com.easy.easyai.core.memory

import java.util.concurrent.ConcurrentHashMap

/**
 * Thread-safe tracker for recording which memory entries were actually accessed
 * by the LLM via memory_read / memory_search tools during a single turn.
 *
 * Accessed entries are collected and used to populate [ContextReferences.memories]
 * on the assistant message, providing accurate reference tracking.
 */
class MemoryAccessTracker {

    private val accessed = ConcurrentHashMap.newKeySet<MemoryRef>()

    /** Record that a memory entry was accessed (read or search hit). */
    fun recordAccess(ref: MemoryRef) {
        accessed.add(ref)
    }

    /** Return all memory entries that were accessed since last [clear]. */
    fun getAccessedRefs(): List<MemoryRef> = accessed.toList()

    /** Reset the tracker for a new turn. */
    fun clear() {
        accessed.clear()
    }
}
