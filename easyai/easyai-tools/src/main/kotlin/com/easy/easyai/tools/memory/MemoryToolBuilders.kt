package com.easy.easyai.tools.memory

import com.easy.easyai.core.domain.DomainCatalog
import com.easy.easyai.core.memory.MemoryContentLimits
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
Retrieval already reports each hit's 'updated' date and 'maturity'; when a hit conflicts with
what you now know, 'update' that entry rather than adding a contradictory one.
For batch operations, pass an 'operations' array instead of single params."""

/** Static guidance for on-demand memory retrieval via memory_* tools (cache-stable). */
private const val MEMORY_GUIDANCE_SEGMENT = """
## Memory

You have access to a persistent memory system via the memory_* tools. Memory content is NOT
included in this prompt to keep it stable for caching — retrieve it on demand instead.

At the START of each new task, proactively call `memory_search` with keywords extracted from
the user's request to recall relevant context: user preferences, past decisions, project
conventions, and prior conclusions. When `knowledge_search` is also available, issue it in the
SAME response as `memory_search` so both run in parallel. Use `memory_read` to load the full
content of a specific entry, and `memory_write` to persist durable facts worth remembering
across sessions. When calling `memory_write`, pass the category via its 'type' parameter
(never inside 'name') and keep 'name' as a bare file name without directories or '.md'.

### Keeping memories current

Each `memory_search` hit carries `updated` and `maturity`; read them to judge staleness.
An entry that contradicts the current code or the user's latest statement must be corrected
with `memory_write action='update'` — never add a rival entry alongside a stale one.
Use `memory_write action='remove'` only for entries proven obsolete or superseded; when
unsure, leave them untouched. `memory_list` adds `unused for N days` for a full review view.

### Drilling into merged memories

Periodic consolidation folds related entries into one higher-level entry. Such a merged hit carries
`level` and lists `sources` — the docIds of the entries it absorbed — and its body is a distillation,
so detail it dropped is still in those sources. When you need the timeline, the cause, or what was
tried and rejected, call `memory_read` with `docIds=[...]` to get the archived bodies back, at most
ten ids per call. What comes back carries its own `sources`, so a chain can be followed deeper.

History is read-only context. Never `memory_write` against a name or path you read there: writing an
active entry onto a history key revives a live duplicate of what the merged entry already states. To
correct a fact you found in history, update the merged entry itself.
"""

@Component
class MemorySearchToolBuilder : AbstractMemoryToolBuilder() {
    override val metadata = ToolMetadata(
        name = "memory_search",
        description = "PRIMARY memory retrieval tool: call this at the start of a task with keywords " +
            "extracted from the user's question to recall relevant memories (user preferences, past " +
            "decisions, project conventions, prior findings). Searches across name, description, and content. " +
            "Optionally restrict results by business time via 'timeRangeStart'/'timeRangeEnd' " +
            "(epoch seconds or ISO date like 2026-01-01). " +
            "Each hit reports its 'updated' date and 'maturity' so staleness can be judged, and a merged " +
            "hit additionally reports its 'level' and the 'sources' docIds it absorbed.",
        permissionCategory = "memory",
        systemPromptSegment = MEMORY_GUIDANCE_SEGMENT.trimIndent(),
        promptSegmentOrder = 20
    )

    override fun createTool(store: MemoryStore): ToolDefinition = MemorySearchTool(metadata, store)
}

@Component
class MemoryReadToolBuilder : AbstractMemoryToolBuilder() {
    override val metadata = ToolMetadata(
        name = "memory_read",
        description = "Read a memory entry in full. Pass 'path' for an active entry (e.g., " +
            "'feedback/testing.md'), or 'docIds' — a JSON array of at most ten docIds taken from a merged " +
            "hit's 'sources' — to read back the archived entries it absorbed.",
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
- 'scenarios': a real JSON array of strings like ["scenario one"]. Never pass it as a single JSON-encoded string.
- 'content': distilled facts, target ~${MemoryContentLimits.CONTENT_TARGET_CHARS} characters; calls over ${MemoryContentLimits.CONTENT_HARD_CAP_CHARS} characters are rejected. Split broader material into multiple focused entries instead of one giant entry.
- 'description': a one-line summary of at most ${MemoryContentLimits.DESCRIPTION_CAP_CHARS} characters."""
        return MemoryWriteTool(metadata.copy(description = metadata.description + parameterRules), store)
    }
}

@Component
class MemoryListToolBuilder : AbstractMemoryToolBuilder() {
    override val metadata = ToolMetadata(
        name = "memory_list",
        description = "List all memory entries. Optionally filter by type (user/feedback/project/reference). " +
            "Shows docId, name, type, description, path, level, dates, maturity and staleness hints for governance review.",
        permissionCategory = "memory"
    )

    override fun createTool(store: MemoryStore): ToolDefinition = MemoryListTool(metadata, store)
}
