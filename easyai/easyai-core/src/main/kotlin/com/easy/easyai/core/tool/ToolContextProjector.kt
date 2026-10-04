package com.easy.easyai.core.tool

import org.slf4j.LoggerFactory

/**
 * Rewrites a tool call's arguments JSON before it is replayed into the LLM context.
 *
 * Declared via [ToolMetadata.contextProjector]; the runtime collects projectors from
 * registered [ToolBuilder]s into a name-keyed map used by message conversion and
 * compaction. Use cases: eliding bulky display-only payloads (HTML/SVG fragments),
 * redacting values the model never needs to re-read.
 *
 * Implementations must be safe on arbitrary input: on any parse/transform failure,
 * return [argumentsJson] unchanged. Callers should still go through [projectSafely],
 * which enforces that contract for implementations that get it wrong.
 */
fun interface ToolContextProjector {
    fun project(argumentsJson: String): String

    companion object {
        private val logger = LoggerFactory.getLogger(ToolContextProjector::class.java)

        /** Collect the name → projector map from registered builders. */
        @JvmStatic
        fun registryFrom(builders: List<ToolBuilder>): Map<String, ToolContextProjector> =
            builders.mapNotNull { builder ->
                builder.contextProjector?.let { builder.name to it }
            }.toMap()

        /**
         * Applies the projector registered for [toolName], falling back to [argumentsJson]
         * unchanged when there is none or when the projector throws.
         */
        @JvmStatic
        fun projectSafely(
            projectors: Map<String, ToolContextProjector>,
            toolName: String,
            argumentsJson: String
        ): String {
            val projector = projectors[toolName] ?: return argumentsJson
            return try {
                projector.project(argumentsJson)
            } catch (e: Exception) {
                logger.warn("Context projector for {} failed, sending arguments as-is: {}", toolName, e.message)
                argumentsJson
            }
        }
    }
}
