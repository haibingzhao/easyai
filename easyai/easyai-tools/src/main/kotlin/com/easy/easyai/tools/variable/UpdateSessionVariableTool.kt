package com.easy.easyai.tools.variable

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.SessionVariables
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.*
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import tools.jackson.core.type.TypeReference
import java.nio.file.Files
import java.nio.file.Path

/**
 * Tool for storing/updating session-scoped variables that persist across
 * context compaction and session resume.
 *
 * Small values (≤500 chars) are stored inline and visible in system prompt every turn.
 * Large values (>500 chars) are saved to files; the variable stores a file path reference.
 */
class UpdateSessionVariableTool(
    metadata: ToolMetadata,
    private val sessionVariables: SessionVariables,
    private val projectPath: Path?,
    private val sessionId: String,
    private val persistCallback: (suspend (String, String?) -> Unit)?
) : BaseToolDefinition(metadata) {

    companion object {
        private val logger = LoggerFactory.getLogger(UpdateSessionVariableTool::class.java)
        private val objectMapper = SharedObjectMapper.instance
        private const val MAX_INLINE_LENGTH = 500
        private const val MAX_VARIABLES = 50
    }

    override val executionMode: ToolExecutionMode = ToolExecutionMode.SEQUENTIAL

    override fun parameterType(): Class<*> = Parameters::class.java

    @Suppress("UNCHECKED_CAST")
    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult = withContext(Dispatchers.IO) {
        try {
            val params = parseParameters(args) ?: return@withContext ToolResult(
                content = listOf(
                    TextContent(
                        text = "Invalid parameters: 'variables' must be a JSON object mapping variable names to " +
                            "string values (e.g. {\"revenue\": \"1.23B\"}), and 'deleteKeys' must be an array of " +
                            "variable name strings. Pass them as structured JSON, not as encoded strings."
                    )
                ),
                isError = true
            )

            val results = mutableListOf<String>()

            // Process variable updates
            params.variables?.forEach { (key, value) ->
                if (sessionVariables.size() >= MAX_VARIABLES && !sessionVariables.getAll().containsKey(key)) {
                    results.add("[FAIL] $key: maximum variable limit ($MAX_VARIABLES) reached")
                    return@forEach
                }
                try {
                    if (value.length <= MAX_INLINE_LENGTH) {
                        sessionVariables.put(key, value)
                        results.add("[OK] $key: stored inline (${value.length} chars)")
                    } else {
                        val filePath = writeToFile(key, value)
                        if (filePath != null) {
                            sessionVariables.put(key, "[file: $filePath]")
                            results.add("[OK] $key: saved to file ($filePath)")
                        } else {
                            // Fallback: store inline even if large
                            sessionVariables.put(key, value)
                            results.add("[OK] $key: stored inline (file write failed, ${value.length} chars)")
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to store variable '{}': {}", key, e.message)
                    results.add("[FAIL] $key: ${e.message}")
                }
            }

            // Process deletions
            params.deleteKeys?.forEach { key ->
                sessionVariables.remove(key)
                deleteFileIfExists(key)
                results.add("[OK] $key: deleted")
            }

            // Persist to DB (synchronous — single-row UPDATE is cheap, avoids out-of-order writes)
            persistCallback?.let { callback ->
                val json = objectMapper.writeValueAsString(sessionVariables.getAll())
                try {
                    callback(sessionId, json)
                } catch (e: Exception) {
                    logger.warn("Failed to persist session variables for {}: {}", sessionId, e.message)
                }
            }

            val summary = if (results.isEmpty()) "No changes made." else results.joinToString("\n")
            ToolResult(content = listOf(TextContent(text = summary)))
        } catch (e: Exception) {
            logger.error("Error executing update_variable tool in tool call {}", toolCallId, e)
            ToolResult(
                content = listOf(TextContent(text = "Failed to update variables: ${e.message}")),
                isError = true
            )
        }
    }

    /**
     * Parse tool arguments into [Parameters], returning null when unparseable.
     * Tolerates models that emit nested parameters as JSON strings via [coerceStringParams].
     */
    private fun parseParameters(args: Map<String, Any?>): Parameters? {
        return try {
            val paramsJson = objectMapper.writeValueAsString(coerceStringParams(args))
            objectMapper.readValue(paramsJson, Parameters::class.java)
        } catch (e: Exception) {
            logger.warn("Failed to parse update_variable parameters: {}", e.message)
            null
        }
    }

    /**
     * Some models serialize nested tool parameters as JSON strings instead of structured
     * objects/arrays (e.g. `variables` arrives as `"{\"k\":\"v\"}"` rather than `{"k":"v"}`).
     * Coerce such string values back to their structured form so deserialization into
     * [Parameters] succeeds regardless of which shape the model emitted.
     */
    private fun coerceStringParams(args: Map<String, Any?>): Map<String, Any?> {
        val coerced = args.toMutableMap()
        (coerced["variables"] as? String)?.let { raw ->
            runCatching { objectMapper.readValue(raw, object : TypeReference<Map<String, String>>() {}) }
                .getOrNull()
                ?.let { coerced["variables"] = it }
        }
        (coerced["deleteKeys"] as? String)?.let { raw ->
            val asList = runCatching { objectMapper.readValue(raw, object : TypeReference<List<String>>() {}) }.getOrNull()
            coerced["deleteKeys"] = asList ?: listOf(raw)
        }
        return coerced
    }

    private fun writeToFile(key: String, value: String): String? {
        val basePath = projectPath ?: return null  // No projectPath = no file storage
        val varsDir = basePath.resolve(".easyai/vars/$sessionId")
        return try {
            Files.createDirectories(varsDir)
            val sanitizedKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
            val filePath = varsDir.resolve("$sanitizedKey.md")
            Files.writeString(filePath, value)
            // Return relative path for prompt display
            ".easyai/vars/$sessionId/$sanitizedKey.md"
        } catch (e: Exception) {
            logger.warn("Failed to write variable file for key '{}': {}", key, e.message)
            null
        }
    }

    private fun deleteFileIfExists(key: String) {
        val basePath = projectPath ?: return
        val sanitizedKey = key.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val filePath = basePath.resolve(".easyai/vars/$sessionId/$sanitizedKey.md")
        try {
            Files.deleteIfExists(filePath)
        } catch (e: Exception) {
            logger.warn("Failed to delete variable file for key '{}': {}", key, e.message)
        }
    }
}

/**
 * Parameters for the UpdateSessionVariableTool.
 */
data class Parameters(
    @param:JsonPropertyDescription("Key-value pairs to store or update. Keys are variable names, values are the data to persist.")
    val variables: Map<String, String>? = null,
    @param:JsonPropertyDescription("List of variable keys to delete.")
    val deleteKeys: List<String>? = null
)
