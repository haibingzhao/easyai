package com.easy.easyai.tools.memory

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.memory.MemoryEntry
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
import kotlinx.coroutines.CoroutineScope
import java.time.LocalDate

internal class MemoryWriteTool(
    metadata: ToolMetadata,
    private val store: MemoryStore
) : BaseToolDefinition(metadata) {

    override val executionMode = ToolExecutionMode.SEQUENTIAL

    data class Parameters(
        val action: String? = null,
        val name: String? = null,
        val type: String? = null,
        val description: String? = null,
        val content: String? = null,
        val oldText: String? = null,
        val scope: String? = null,
        val operations: List<Operation>? = null
    )

    data class Operation(
        val action: String,
        val name: String,
        val type: String? = null,
        val description: String? = null,
        val content: String? = null,
        val oldText: String? = null
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
            SharedObjectMapper.instance.convertValue(args, Parameters::class.java)
        } catch (e: Exception) {
            return errorResult("Error: Invalid parameters: ${e.message}")
        }

        val scope = resolveScope(params.scope)

        // Batch mode: operations array
        if (params.operations != null) {
            return executeBatch(agentContext, params.operations, scope)
        }

        // Single operation mode
        val action = params.action ?: return errorResult("Error: 'action' is required (add/update/remove).")
        val name = params.name ?: return errorResult("Error: 'name' is required.")

        return executeSingle(agentContext, action, name, params.type, params.description, params.content, params.oldText, scope)
    }

    private suspend fun executeSingle(
        agentContext: AgentContext,
        action: String,
        name: String,
        type: String?,
        description: String?,
        content: String?,
        oldText: String?,
        scope: MemoryScope
    ): ToolResult {
        return when (action.lowercase()) {
            "add" -> handleAdd(agentContext, name, type, description, content, scope)
            "update" -> handleUpdate(agentContext, name, content, oldText, scope)
            "remove" -> handleRemove(agentContext, name, scope)
            else -> errorResult("Error: Unknown action '$action'. Use 'add', 'update', or 'remove'.")
        }
    }

    private suspend fun handleAdd(
        agentContext: AgentContext,
        name: String, type: String?, description: String?, content: String?, scope: MemoryScope
    ): ToolResult {
        if (type.isNullOrBlank()) return errorResult("Error: 'type' is required for add (user/feedback/project/reference).")
        if (description.isNullOrBlank()) return errorResult("Error: 'description' is required for add.")
        if (content.isNullOrBlank()) return errorResult("Error: 'content' is required for add.")

        val memoryType = MemoryType.fromDirName(type)
            ?: return errorResult("Error: Unknown type '$type'. Use: user, feedback, project, reference.")

        // Name dedup check
        if (store.exists(agentContext, name, scope)) {
            return errorResult("Entry '$name' already exists in ${scope.name.lowercase()} scope. Use action='update' to modify it.")
        }

        val entry = MemoryEntry(
            name = name,
            description = description,
            type = memoryType,
            content = content,
            path = "${memoryType.dirName}/$name.md",
            created = LocalDate.now(),
            updated = LocalDate.now()
        )
        val path = store.write(agentContext, entry, scope)
        return ToolResult(content = listOf(TextContent("Memory entry created: $path")))
    }

    private suspend fun handleUpdate(
        agentContext: AgentContext,
        name: String, content: String?, oldText: String?, scope: MemoryScope
    ): ToolResult {
        if (content.isNullOrBlank()) return errorResult("Error: 'content' is required for update.")

        val existing = store.findByName(agentContext, name, scope)
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
        val path = store.write(agentContext, entry, scope)
        return ToolResult(content = listOf(TextContent("Memory entry updated: $path")))
    }

    private suspend fun handleRemove(agentContext: AgentContext, name: String, scope: MemoryScope): ToolResult {
        val existing = store.findByName(agentContext, name, scope)
            ?: return errorResult("Entry '$name' not found in ${scope.name.lowercase()} scope.")

        val deleted = store.delete(agentContext, existing.path, scope)
        return if (deleted) {
            ToolResult(content = listOf(TextContent("Memory entry removed: ${existing.path}")))
        } else {
            errorResult("Failed to delete entry '$name'.")
        }
    }

    private suspend fun executeBatch(agentContext: AgentContext, operations: List<Operation>, scope: MemoryScope): ToolResult {
        if (operations.isEmpty()) return errorResult("Error: 'operations' array must not be empty.")

        val results = mutableListOf<String>()
        // Snapshot for rollback: track created paths, deleted entries, and original entries before update
        val createdPaths = mutableListOf<String>()
        val rollbackActions = mutableListOf<suspend () -> Unit>()

        try {
            for ((i, op) in operations.withIndex()) {
                // Capture snapshot BEFORE executing for rollback of update/remove
                val preExisting = when (op.action.lowercase()) {
                    "update" -> store.findByName(agentContext, op.name, scope)
                    "remove" -> store.findByName(agentContext, op.name, scope)
                    else -> null
                }

                val result = executeSingle(agentContext, op.action, op.name, op.type, op.description, op.content, op.oldText, scope)
                if (result.isError) {
                    safeRollback(agentContext, createdPaths, rollbackActions, scope)
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
                            rollbackActions.add { store.write(agentContext, preExisting, scope) }
                        }
                    }
                    "update" -> {
                        if (preExisting != null) {
                            rollbackActions.add { store.write(agentContext, preExisting, scope) }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            safeRollback(agentContext, createdPaths, rollbackActions, scope)
            return errorResult("Batch operation aborted due to exception: ${e.message}. Partial rollback performed.")
        }

        return ToolResult(content = listOf(TextContent("Batch completed (${operations.size} operations):\n${results.joinToString("\n")}")))
    }

    private suspend fun safeRollback(
        agentContext: AgentContext,
        createdPaths: List<String>,
        rollbackActions: List<suspend () -> Unit>,
        scope: MemoryScope
    ) {
        // Delete created entries (best effort)
        for (path in createdPaths) {
            try { store.delete(agentContext, path, scope) } catch (_: Exception) { /* best effort */ }
        }
        // Restore deleted/updated entries (best effort)
        for (action in rollbackActions.reversed()) {
            try { action() } catch (_: Exception) { /* best effort */ }
        }
    }

    private fun resolveScope(scopeStr: String?): MemoryScope = when (scopeStr?.lowercase()) {
        "global" -> MemoryScope.GLOBAL
        else -> MemoryScope.PROJECT
    }
}
