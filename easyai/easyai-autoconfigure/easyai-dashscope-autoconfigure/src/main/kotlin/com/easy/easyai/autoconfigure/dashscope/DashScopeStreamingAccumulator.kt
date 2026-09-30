package com.easy.easyai.autoconfigure.dashscope

import org.slf4j.LoggerFactory

/**
 * One streamed frame after tool-call fragments have been folded into complete calls.
 *
 * [toolCalls] is a snapshot of everything accumulated so far, not a delta: the agent loop only
 * reads tool calls from the last content-bearing chunk, so every frame has to carry the full
 * accumulated arguments for the final message to be usable.
 */
internal data class DashScopeAccumulatedFrame(
    val reasoningDelta: String?,
    val textDelta: String?,
    val toolCalls: List<DashScopeToolCallSpec>,
    val finishReason: String?,
    val usage: DashScopeTokenUsage?,
    val error: DashScopeError?
)

/**
 * Folds DashScope's incremental `tool_calls` frames into complete calls.
 *
 * Bailian gateways stream tool calls either as positional fragments (each frame carries one
 * partial call, arguments appended) or as a growing array snapshot. The two are indistinguishable
 * from a single frame, so arguments are merged by prefix: an incoming value that already starts
 * with what was accumulated is read as a snapshot and replaces it, otherwise it is appended.
 */
internal class DashScopeStreamingAccumulator {

    private val logger = LoggerFactory.getLogger(DashScopeStreamingAccumulator::class.java)

    private class Slot(var id: String?, var name: String?) {
        val arguments = StringBuilder()

        fun merge(fragment: DashScopeToolCallFragment) {
            fragment.id?.takeIf { it.isNotBlank() }?.let { id = it }
            fragment.name?.takeIf { it.isNotBlank() }?.let { name = it }
            val incoming = fragment.arguments ?: return
            if (incoming.isEmpty()) return
            if (incoming.startsWith(arguments)) {
                // A snapshot repeats everything sent so far, so it replaces rather than appends.
                arguments.setLength(0)
                arguments.append(incoming)
            } else {
                arguments.append(incoming)
            }
        }
    }

    private val slots = LinkedHashMap<Int, Slot>()

    fun accept(chunk: DashScopeChunk): DashScopeAccumulatedFrame {
        val fragments = chunk.toolCallFragments
        for (fragment in fragments) {
            val slot = slots.getOrPut(fragment.slot) { Slot(fragment.id, fragment.name) }
            slot.merge(fragment)
        }
        val hasCalls = slots.isNotEmpty()
        return DashScopeAccumulatedFrame(
            reasoningDelta = chunk.reasoningDelta,
            textDelta = chunk.textDelta,
            toolCalls = if (hasCalls) snapshot() else emptyList(),
            // A missing finish reason is left as-is: the agent loop derives TOOL_USE from the
            // accumulated calls themselves, which is more reliable than a provider flag.
            finishReason = chunk.finishReason,
            usage = chunk.usage,
            error = chunk.error
        )
    }

    /** Complete calls in arrival order; a call that never received an id and name is unexecutable. */
    fun snapshot(): List<DashScopeToolCallSpec> = slots.values.mapNotNull { slot ->
        val name = slot.name
        val id = slot.id
        if (name.isNullOrBlank() || id.isNullOrBlank()) {
            logger.warn("Dropping an incomplete tool call (id {}, name {})", id, name)
            null
        } else {
            DashScopeToolCallSpec(id = id, name = name, arguments = slot.arguments.toString())
        }
    }
}
