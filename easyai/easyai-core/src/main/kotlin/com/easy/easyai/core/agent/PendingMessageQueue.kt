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
    fun enqueueWithType(message: EasyAiMessage, type: String): String = synchronized(entries) {
        val id = nextId()
        entries.add(QueueEntry(id = id, message = message, type = type))
        id
    }

    /**
     * Poll messages from the queue using the given mode.
     * Removes and returns the polled entries (consumer API for AgentLoop).
     */
    fun poll(mode: Mode): List<EasyAiMessage> = synchronized(entries) {
        if (entries.isEmpty()) return emptyList()
        when (mode) {
            Mode.ALL -> {
                val list = entries.map { it.message }
                entries.clear()
                list
            }
            Mode.ONE_AT_A_TIME -> listOf(entries.removeAt(0).message)
        }
    }

    /**
     * Remove a queued message by ID. Returns true if found and removed.
     */
    fun remove(id: String): Boolean = synchronized(entries) {
        entries.removeIf { it.id == id }
    }

    /** Return the current user message snapshot, or null if absent or not editable. */
    fun get(id: String): UserMessage? = synchronized(entries) {
        entries.find { it.id == id }?.message as? UserMessage
    }

    /**
     * Replace only the exact snapshot returned by [get], preserving queue ID, type and order.
     * Prepare command metadata upstream before calling this method. A concurrent edit or
     * consumption makes the snapshot stale and returns false, even when the message ID matches.
     */
    fun updateMessage(id: String, expectedSnapshot: UserMessage, replacement: UserMessage): Boolean = synchronized(entries) {
        val index = entries.indexOfFirst { it.id == id }
        if (index < 0 || entries[index].message !== expectedSnapshot) return false
        entries[index] = entries[index].copy(message = replacement)
        true
    }

    /**
     * Update text while retaining attachments and non-command metadata.
     * Command snapshots are invalidated: callers editing commands must prepare a replacement
     * upstream and use [updateMessage] instead, so old expansions never survive a text edit.
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
                    msg.copy(
                        content = listOf(TextContent(newContent)) + nonTextBlocks,
                        metadata = msg.metadata - setOf(
                            UserMessage.COMMAND_EXPANSION, UserMessage.COMMAND_NAME,
                            UserMessage.COMMAND_SOURCE, UserMessage.COMMAND_CATEGORY,
                            UserMessage.COMMAND_USER_ID, UserMessage.COMMAND_PROJECT_PATH
                        )
                    )
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
        synchronized(entries) {
            if (entries.size <= 1) return
            val snapshot = ArrayList(entries)
            val ordered = mutableListOf<QueueEntry>()
            val idSet = ids.toHashSet()

            // Add entries in the requested order without duplicating consumable entries
            for (id in ids.distinct()) {
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
    fun peekAll(): List<QueuedMessageInfo> = synchronized(entries) {
        entries.map { it.toInfo() }
    }

    /**
     * Check if the queue is empty.
     */
    fun isEmpty(): Boolean = synchronized(entries) { entries.isEmpty() }

    /**
     * Return the current queue size.
     */
    fun size(): Int = synchronized(entries) { entries.size }
}
