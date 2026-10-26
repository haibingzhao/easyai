package com.easy.easyai.core.agent

import com.easy.easyai.common.util.SharedObjectMapper
import tools.jackson.databind.JsonNode

/**
 * Per-server whitelist carried by an [AgentToolConfig] with `targetType = MCP`.
 *
 * An empty list means "every tool/prompt of that server is allowed", matching the runtime
 * filter semantics. Two on-disk formats are read:
 * - legacy array `["tool1","tool2"]` — tool whitelist only, prompt whitelist implied empty;
 * - current object `{"toolNames":[...],"promptNames":[...]}`.
 *
 * Only the object form is written; the legacy array is read-only.
 */
data class McpBindingSpec(
    val toolNames: List<String> = emptyList(),
    val promptNames: List<String> = emptyList()
) {
    /** No whitelist at all — the whole server is allowed. */
    val isEmpty: Boolean get() = toolNames.isEmpty() && promptNames.isEmpty()

    /** Metadata JSON for [AgentToolConfig.metadata]; null when [isEmpty]. */
    fun toMetadata(): String? {
        if (isEmpty) return null
        val payload = mutableMapOf<String, List<String>>()
        if (toolNames.isNotEmpty()) payload["toolNames"] = toolNames
        if (promptNames.isNotEmpty()) payload["promptNames"] = promptNames
        return SharedObjectMapper.instance.writeValueAsString(payload)
    }

    companion object {
        /**
         * Parse stored metadata. Null, blank and malformed input all yield the empty spec, so a
         * corrupted row degrades to "whole server allowed" instead of failing the caller.
         */
        @JvmStatic
        fun parse(metadata: String?): McpBindingSpec = parseOrNull(metadata) ?: McpBindingSpec()

        /**
         * Parse stored metadata, returning null when it is absent or unparsable. Callers that must
         * fail open can still tell that case apart from a valid but empty whitelist (`{}`).
         */
        @JvmStatic
        fun parseOrNull(metadata: String?): McpBindingSpec? {
            if (metadata.isNullOrBlank()) return null
            return try {
                fromNode(SharedObjectMapper.instance.readTree(metadata))
            } catch (_: Exception) {
                null
            }
        }

        /** Parse an already-deserialized node; a bare array is read as the legacy tool whitelist. */
        @JvmStatic
        fun fromNode(node: JsonNode?): McpBindingSpec = when {
            node == null -> McpBindingSpec()
            node.isArray -> McpBindingSpec(toolNames = stringArray(node))
            else -> McpBindingSpec(
                toolNames = stringArray(node.get("toolNames")),
                promptNames = stringArray(node.get("promptNames"))
            )
        }

        private fun stringArray(node: JsonNode?): List<String> =
            if (node != null && node.isArray) node.toList().map { it.asString() } else emptyList()
    }
}
