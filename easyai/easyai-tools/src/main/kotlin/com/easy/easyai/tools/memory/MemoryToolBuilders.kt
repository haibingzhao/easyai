package com.easy.easyai.tools.memory

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.MemoryStore
import com.easy.easyai.core.memory.MemoryType
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
        description = "PRIMARY memory retrieval tool: call this at the start of a task with keywords " +
            "extracted from the user's question to recall relevant memories (user preferences, past " +
            "decisions, project conventions, prior findings). Searches across name, description, and content. " +
            "Optionally restrict results by business time via 'timeRangeStart'/'timeRangeEnd' " +
            "(epoch seconds or ISO date like 2026-01-01).",
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

    override fun createTool(store: MemoryStore): ToolDefinition {
        // Inject the active domain's valid type list into the LLM-visible description so the
        // model can emit a valid 'type' on the first attempt instead of failing with an enum error.
        val validTypes = MemoryType.entriesFor(DomainCatalog.activeDomain).joinToString(", ") { it.dirName }
        val parameterRules = """

Parameter rules (violating any of these is the most common call failure):
- 'type': must be exactly one of: $validTypes. Never invent aliases such as 'project' or 'user'.
- 'name': bare file name only - no directory segments (no '/'), no '.md' suffix. The category goes into 'type', NOT into 'name'.
- 'scenarios': a real JSON array of strings like ["scenario one"]. Never pass it as a single JSON-encoded string."""
        return MemoryWriteTool(metadata.copy(description = metadata.description + parameterRules), store)
    }
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
