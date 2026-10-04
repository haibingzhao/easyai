package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

/** Static guidance for on-demand knowledge retrieval via knowledge_* tools (cache-stable). */
private const val KNOWLEDGE_GUIDANCE_SEGMENT = """
## Knowledge Base

You have access to a knowledge base via the knowledge_* tools. Knowledge content is NOT
included in this prompt to keep it stable for caching — retrieve it on demand instead.

At the START of each new task, proactively call `knowledge_search` with keywords extracted
from the user's request to retrieve relevant documents. When `memory_search` is also
available, you MUST issue both calls in the SAME response so they run in parallel.
Use `knowledge_read` to load the full content of a specific entry by its key.
"""

@Component
class KnowledgeSearchToolBuilder : AbstractKnowledgeToolBuilder() {
    override val metadata = ToolMetadata(
        name = "knowledge_search",
        description = "Semantic retrieval over the knowledge base: call this at the start of a task with " +
            "keywords extracted from the user's question to retrieve relevant documents. Issue it in the " +
            "SAME response as 'memory_search' (when available) so both run in parallel. Optionally filter " +
            "by 'source' or 'kcategory'.",
        permissionCategory = "knowledge",
        systemPromptSegment = KNOWLEDGE_GUIDANCE_SEGMENT.trimIndent(),
        promptSegmentOrder = 30
    )

    override fun createTool(store: KnowledgeStore): ToolDefinition = KnowledgeSearchTool(metadata, store)
}

@Component
class KnowledgeReadToolBuilder : AbstractKnowledgeToolBuilder() {
    override val metadata = ToolMetadata(
        name = "knowledge_read",
        description = "Read the full content of a specific knowledge entry by its key " +
            "(e.g., 'my-docs/architecture.md'), as returned by knowledge_search.",
        permissionCategory = "knowledge"
    )

    override fun createTool(store: KnowledgeStore): ToolDefinition = KnowledgeReadTool(metadata, store)
}
