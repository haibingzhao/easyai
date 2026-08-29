package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.MemoryEntry
import com.easy.easyai.core.memory.MemoryMaturity
import com.easy.easyai.core.memory.MemoryOwnerContext
import com.easy.easyai.core.memory.MemoryScope
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolExecutionMode
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.common.util.SharedObjectMapper
import com.fasterxml.jackson.annotation.JsonPropertyDescription
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate

internal class MemoryWriteTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        @field:JsonPropertyDescription("Operation type: 'add', 'update' or 'remove'. Required unless batch 'operations' is provided.")
        val action: String? = null,
        @field:JsonPropertyDescription("Bare entry file name WITHOUT directories and WITHOUT '.md' extension; must not contain '/', '\\' or '..'. The category goes in 'type', NOT in the name. Example: 'stage2_2026-08-27_conclusion'")
        val name: String? = null,
        @field:JsonPropertyDescription("Category directory name - one of the valid types listed in this tool's description. Never put the category inside 'name'.")
        val type: String? = null,
        @field:JsonPropertyDescription("One-line summary of what the memory captures. Required for add.")
        val description: String? = null,
        @field:JsonPropertyDescription("Full body text of the entry. Required for add and update.")
        val content: String? = null,
        @field:JsonPropertyDescription("Exact substring to replace during update; omit to replace the whole content.")
        val oldText: String? = null,
        @field:JsonPropertyDescription("'project' (default) or 'global'.")
        val scope: String? = null,
        @field:JsonPropertyDescription("Optional maturity tag, e.g. 'high', 'medium', 'low'.")
        val maturity: String? = null,
        @field:JsonPropertyDescription("A real JSON array of strings describing when this memory applies, e.g. [\"debugging\", \"code review\"]. Never pass a single JSON-encoded string here.")
        val scenarios: List<String>? = null,
        @field:JsonPropertyDescription("Batch mode: list of operation objects {action, name, type, description, content, oldText, maturity, scenarios} executed atomically with rollback on failure.")
        val operations: List<Operation>? = null
    )

    data class Operation(
        @field:JsonPropertyDescription("'add', 'update' or 'remove'.")
        val action: String,
        @field:JsonPropertyDescription("Bare entry file name without directories, '.md' extension or path separators.")
        val name: String,
        @field:JsonPropertyDescription("Category directory name from this tool's description; required for add.")
        val type: String? = null,
        @field:JsonPropertyDescription("One-line summary. Required for add.")
        val description: String? = null,
        @field:JsonPropertyDescription("Full body text. Required for add and update.")
        val content: String? = null,
        @field:JsonPropertyDescription("Exact substring to replace during update.")
        val oldText: String? = null,
        @field:JsonPropertyDescription("Optional maturity tag.")
        val maturity: String? = null,
        @field:JsonPropertyDescription("A real JSON array of strings; never a single JSON-encoded string.")
        val scenarios: List<String>? = null
    )

    override fun parameterType(): Class<*> = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val params = try {
            SharedObjectMapper.instance.convertValue(normalizeScenarioArgs(args), Parameters::class.java)
        } catch (e: Exception) {
            return errorResult("Error: Invalid parameters: ${e.message}")
        }

        val scope = resolveScope(params.scope)
        val owner = MemoryOwnerContext(agentContext.userId, agentContext.projectPath)

        // Batch mode: operations array
        if (params.operations != null) {
            return executeBatch(params.operations, scope, owner)
        }

        // Single operation mode
        val action = params.action ?: return errorResult("Error: 'action' is required (add/update/remove).")
        val name = params.name ?: return errorResult("Error: 'name' is required.")

        return executeSingle(
            action, name, params.type, params.description, params.content, params.oldText,
            params.maturity, params.scenarios, scope, owner
        )
    }

    private suspend fun executeSingle(
        action: String,
        name: String,
        type: String?,
        description: String?,
        content: String?,
        oldText: String?,
        maturity: String?,
        scenarios: List<String>?,
        scope: MemoryScope,
        owner: MemoryOwnerContext
    ): ToolResult {
        return when (action.lowercase()) {
            "add" -> handleAdd(name, type, description, content, maturity, scenarios, scope, owner)
            "update" -> handleUpdate(name, content, oldText, scope, owner)
            "remove" -> handleRemove(name, scope, owner)
            else -> errorResult("Error: Unknown action '$action'. Use 'add', 'update', or 'remove'.")
        }
    }

    private suspend fun handleAdd(
        name: String, type: String?, description: String?, content: String?,
        maturity: String?, scenarios: List<String>?, scope: MemoryScope, owner: MemoryOwnerContext
    ): ToolResult {
        val validTypes = MemoryType.entriesFor(DomainCatalog.activeDomain)

        // Report every violated parameter at once so the caller can fix them all in a single retry.
        if (type.isNullOrBlank() || description.isNullOrBlank() || content.isNullOrBlank() || !isValidMemoryName(name)) {
            val problems = mutableListOf<String>()
            if (type.isNullOrBlank()) problems.add("- 'type' is required for add; valid values: ${validTypes.joinToString(", ") { it.dirName }}")
            if (description.isNullOrBlank()) problems.add("- 'description' is required for add")
            if (content.isNullOrBlank()) problems.add("- 'content' is required for add")
            if (!isValidMemoryName(name)) {
                problems.add(
                    "- 'name' must be a bare file name without directory separators ('/') and without '.md'; " +
                        "the category belongs in 'type', not in the name"
                )
            }
            return errorResult("Error: invalid parameters for add:\n${problems.joinToString("\n")}")
        }

        val memoryType = MemoryType.fromDirName(type)
            ?: return errorResult("Error: Unknown type '$type'. Use: ${validTypes.joinToString(", ") { it.dirName }}.")
        if (memoryType !in validTypes) {
            return errorResult("Error: Type '$type' is not available in the current domain. Use: ${validTypes.joinToString(", ") { it.dirName }}.")
        }

        // Name dedup check
        if (store.exists(name, scope, owner)) {
            return errorResult("Entry '$name' already exists in ${scope.name.lowercase()} scope. Use action='update' to modify it.")
        }

        val entry = MemoryEntry(
            name = name,
            description = description,
            type = memoryType,
            content = content,
            path = "${memoryType.dirName}/$name.md",
            keywords = emptyList(),
            created = LocalDate.now(),
            updated = LocalDate.now(),
            maturity = maturity?.let { MemoryMaturity.fromApiName(it) },
            scenarios = scenarios?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        )
        val path = store.write(entry, scope, owner)
        return ToolResult(content = listOf(TextContent("Memory entry created: $path")))
    }

    private suspend fun handleUpdate(
        name: String, content: String?, oldText: String?, scope: MemoryScope, owner: MemoryOwnerContext
    ): ToolResult {
        if (content.isNullOrBlank()) return errorResult("Error: 'content' is required for update.")

        val existing = store.findByName(name, scope, owner)
            ?: return errorResult("Entry '$name' not found in ${scope.name.lowercase()} scope. Use action='add' to create it.")

        val updatedContent = if (oldText != null) {
            // Substring replacement
            val occurrences = existing.content.split(oldText).size - 1
            when {
                occurrences == 0 -> return errorResult("old_text not found in entry '$name'.")
                occurrences > 1 -> return errorResult("old_text matches $occurrences locations in '$name'. Provide a more specific substring.")
                else -> existing.content.replace(oldText, content)
            }
        } else {
            content
        }

        val entry = existing.copy(
            content = updatedContent,
            updated = LocalDate.now()
        )
        val path = store.write(entry, scope, owner)
        return ToolResult(content = listOf(TextContent("Memory entry updated: $path")))
    }

    private suspend fun handleRemove(name: String, scope: MemoryScope, owner: MemoryOwnerContext): ToolResult {
        val existing = store.findByName(name, scope, owner)
            ?: return errorResult("Entry '$name' not found in ${scope.name.lowercase()} scope.")

        val deleted = store.delete(existing.path, scope, owner)
        return if (deleted) {
            ToolResult(content = listOf(TextContent("Memory entry removed: ${existing.path}")))
        } else {
            errorResult("Failed to delete entry '$name'.")
        }
    }

    private suspend fun executeBatch(operations: List<Operation>, scope: MemoryScope, owner: MemoryOwnerContext): ToolResult {
        if (operations.isEmpty()) return errorResult("Error: 'operations' array must not be empty.")

        val results = mutableListOf<String>()
        // Snapshot for rollback: track created paths, deleted entries, and original entries before update
        val createdPaths = mutableListOf<String>()
        val rollbackActions = mutableListOf<suspend () -> Unit>()

        try {
            for ((i, op) in operations.withIndex()) {
                // Capture snapshot BEFORE executing for rollback of update/remove
                val preExisting = when (op.action.lowercase()) {
                    "update" -> store.findByName(op.name, scope, owner)
                    "remove" -> store.findByName(op.name, scope, owner)
                    else -> null
                }

                val result = executeSingle(op.action, op.name, op.type, op.description, op.content, op.oldText, op.maturity, op.scenarios, scope, owner)
                if (result.isError) {
                    safeRollback(createdPaths, rollbackActions, scope, owner)
                    return errorResult(
                        "Batch operation failed at index $i (${op.action} '${op.name}'): " +
                            "${(result.content.firstOrNull() as? TextContent)?.text}. " +
                            "Created entries rolled back; updated/removed entries restored from snapshot."
                    )
                }
                results.add("[$i] ${(result.content.firstOrNull() as? TextContent)?.text}")
                when (op.action.lowercase()) {
                    "add" -> {
                        val path = "${op.type}/${op.name}.md"
                        createdPaths.add(path)
                    }
                    "remove" -> {
                        if (preExisting != null) {
                            rollbackActions.add { store.write(preExisting, scope, owner) }
                        }
                    }
                    "update" -> {
                        if (preExisting != null) {
                            rollbackActions.add { store.write(preExisting, scope, owner) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            safeRollback(createdPaths, rollbackActions, scope, owner)
            return errorResult("Batch operation aborted due to exception: ${e.message}. Partial rollback performed.")
        }

        return ToolResult(content = listOf(TextContent("Batch completed (${operations.size} operations):\n${results.joinToString("\n")}")))
    }

    private suspend fun safeRollback(
        createdPaths: List<String>,
        rollbackActions: List<suspend () -> Unit>,
        scope: MemoryScope,
        owner: MemoryOwnerContext
    ) {
        // Delete created entries (best effort)
        for (path in createdPaths) {
            try { store.delete(path, scope, owner) } catch (_: Exception) { /* best effort */ }
        }
        // Restore deleted/updated entries (best effort)
        for (action in rollbackActions.reversed()) {
            try { action() } catch (_: Exception) { /* best effort */ }
        }
    }

    /**
     * Normalize LLM-provided scenario arguments before deserialization:
     * a JSON-encoded string ("[\"s1\",\"s2\"]") is parsed back into a real string array;
     * any other scalar is wrapped as a single-element array.
     */
    private fun normalizeScenarioArgs(args: Map<String, Any?>): Map<String, Any?> {
        val result = HashMap<String, Any?>(args)
        args["scenarios"]?.let { result["scenarios"] = coerceToStringList(it) }
        (args["operations"] as? List<*>)?.let { ops ->
            result["operations"] = ops.map { op ->
                if (op is Map<*, *> && op.containsKey("scenarios")) {
                    @Suppress("UNCHECKED_CAST")
                    val mutable = HashMap(op as Map<Any?, Any?>)
                    mutable["scenarios"] = coerceToStringList(mutable["scenarios"])
                    mutable
                } else {
                    op
                }
            }
        }
        return result
    }

    private fun coerceToStringList(value: Any?): Any? {
        if (value !is String) return value
        return runCatching {
            val node = SharedObjectMapper.instance.readTree(value)
            if (node.isArray) node.toList().map { it.asString() } else listOf(value)
        }.getOrElse { listOf(value) }
    }

    private fun resolveScope(scopeStr: String?): MemoryScope = when (scopeStr?.lowercase()) {
        "global" -> MemoryScope.GLOBAL
        else -> MemoryScope.PROJECT
    }

    /** Validate memory name to prevent path traversal in file storage. */
    private fun isValidMemoryName(name: String): Boolean =
        name.isNotBlank() && !name.contains("/") && !name.contains("\\") && !name.contains("..")
}
