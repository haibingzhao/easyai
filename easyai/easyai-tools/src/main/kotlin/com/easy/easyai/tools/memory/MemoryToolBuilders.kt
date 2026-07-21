package com.easy.easyai.tools.memory

import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

private const val MEMORY_WRITE_DESCRIPTION = """Save durable facts to persistent memory that survive across sessions.
Prioritize what reduces future user steering — the most valuable memory
prevents the user from having to correct or remind you again.

Do NOT save: task progress, PR/issue numbers, commit SHAs, 'fixed bug X',
'Phase N done', or any artifact stale in 7 days.

Write as declarative facts, not instructions to yourself.
✓ 'User prefers concise responses'  ✗ 'Always respond concisely'
✓ 'Project uses Kotlin + Spring Boot'  ✗ 'Use Kotlin for backend'

Actions: 'add' creates new, 'update' modifies existing, 'remove' deletes.
Use memory_list first to see existing entries before updating.
For batch operations, pass an 'operations' array instead of single params."""

@Component
class MemorySearchToolBuilder : AbstractMemoryToolBuilder() {
    override val metadata = ToolMetadata(
        name = "memory_search",
        description = "Search memory entries by keyword. Searches across name, description, and content.",
        permissionCategory = "memory"
    )

    override fun createTool(store: MemoryStore): ToolDefinition = MemorySearchTool(metadata, store)
}

@Component
class MemoryReadToolBuilder : AbstractMemoryToolBuilder() {
    override val metadata = ToolMetadata(
        name = "memory_read",
        description = "Read the full content of a specific memory file by its path (e.g., 'feedback/testing.md').",
        permissionCategory = "memory"
    )

    override fun createTool(store: MemoryStore): ToolDefinition = MemoryReadTool(metadata, store)
}

@Component
class MemoryWriteToolBuilder : AbstractMemoryToolBuilder() {
    override val metadata = ToolMetadata(
        name = "memory_write",
        description = MEMORY_WRITE_DESCRIPTION,
        permissionCategory = "memory",
        tracksFileChanges = true
    )

    override fun createTool(store: MemoryStore): ToolDefinition = MemoryWriteTool(metadata, store)
}

@Component
class MemoryListToolBuilder : AbstractMemoryToolBuilder() {
    override val metadata = ToolMetadata(
        name = "memory_list",
        description = "List all memory entries. Optionally filter by type (user/feedback/project/reference). " +
            "Shows name, type, description, path, and dates for each entry.",
        permissionCategory = "memory"
    )

    override fun createTool(store: MemoryStore): ToolDefinition = MemoryListTool(metadata, store)
}
