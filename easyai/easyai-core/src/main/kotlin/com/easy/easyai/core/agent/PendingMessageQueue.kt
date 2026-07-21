package com.easy.easyai.core.agent

import com.easy.easyai.core.model.EasyAiMessage
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.model.UserMessage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/**
 * DTO representing a queued message visible to the frontend.
 */
data class QueuedMessageInfo(
    val id: String,
    val content: String,
    val type: String // "steer" | "followUp"
)

/**
 * Internal entry stored in the queue, wrapping a message with a unique ID and type.
 */
internal data class QueueEntry(
    val id: String,
    val message: EasyAiMessage,
    val type: String // "steer" | "followUp"
) {
    fun toInfo(): QueuedMessageInfo {
        val text = message.content.filterIsInstance<TextContent>().joinToString("") { it.text }
        return QueuedMessageInfo(id = id, content = text, type = type)
    }
}

/**
 * Thread-safe message queue used for steering and follow-up messages.
 * Shared between [ChatSession] (producer) and [AgentRunner] (consumer).
 *
 * Supports CRUD operations (add, remove, update, reorder) required by the
 * frontend queue UI, in addition to the original poll/enqueue consumption API.
 */
class PendingMessageQueue {
    enum class Mode { ALL, ONE_AT_A_TIME }

    private val entries = CopyOnWriteArrayList<QueueEntry>()
    private val idCounter = AtomicLong(0)

    private fun nextId(): String = "q_${System.nanoTime()}_${idCounter.incrementAndGet()}"


    /**
     * Enqueue a message with an explicit type and return the generated queue ID.
     */
    fun enqueueWithType(message: EasyAiMessage, type: String): String {
        val id = nextId()
        entries.add(QueueEntry(id = id, message = message, type = type))
        return id
    }

    /**
     * Poll messages from the queue using the given mode.
     * Removes and returns the polled entries (consumer API for AgentLoop).
     */
    fun poll(mode: Mode): List<EasyAiMessage> {
        if (entries.isEmpty()) return emptyList()
        return when (mode) {
            Mode.ALL -> {
                // Atomic drain: snapshot + clear under the same lock
                synchronized(entries) {
                    val list = entries.map { it.message }
                    entries.clear()
                    list
                }
            }
            Mode.ONE_AT_A_TIME -> {
                if (entries.isEmpty()) emptyList()
                else {
                    val entry = entries.removeAt(0)
                    listOf(entry.message)
                }
            }
        }
    }

    /**
     * Remove a queued message by ID. Returns true if found and removed.
     */
    fun remove(id: String): Boolean {
        return entries.removeIf { it.id == id }
    }

    /**
     * Update the content of a queued message by ID.
     * Replaces the underlying [UserMessage] with a new one containing the updated text.
     * Returns true if found and updated.
     */
    fun update(id: String, newContent: String): Boolean {
        synchronized(entries) {
            val index = entries.indexOfFirst { it.id == id }
            if (index < 0) return false
            val existing = entries[index]
            val newMessage = when (val msg = existing.message) {
                is UserMessage -> {
                    val nonTextBlocks = msg.content.filter { it !is TextContent }
                    msg.copy(content = listOf(TextContent(newContent)) + nonTextBlocks)
                }
                else -> return false // Only UserMessage is supported for update
            }
            entries[index] = existing.copy(message = newMessage)
            return true
        }
    }

    /**
     * Reorder queued messages to match the given ID order.
     * IDs not in the list are appended at the end in their original order.
     */
    fun reorder(ids: List<String>) {
        if (entries.size <= 1) return
        synchronized(entries) {
            val snapshot = ArrayList(entries)
            val ordered = mutableListOf<QueueEntry>()
            val idSet = ids.toHashSet()

            // Add entries in the requested order
            for (id in ids) {
                val entry = snapshot.find { it.id == id }
                if (entry != null) ordered.add(entry)
            }
            // Append any entries not in the reorder list (preserve original order)
            for (entry in snapshot) {
                if (entry.id !in idSet) ordered.add(entry)
            }
            entries.clear()
            entries.addAll(ordered)
        }
    }

    /**
     * Return a snapshot of all queued messages with their IDs and types.
     */
    fun peekAll(): List<QueuedMessageInfo> {
        return entries.map { it.toInfo() }
    }

    /**
     * Check if the queue is empty.
     */
    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * Return the current queue size.
     */
    fun size(): Int = entries.size
}
