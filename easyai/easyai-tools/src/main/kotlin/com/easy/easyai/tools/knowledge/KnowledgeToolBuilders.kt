package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.stereotype.Component

@Component
class KnowledgeSearchToolBuilder : AbstractKnowledgeToolBuilder() {
    override val metadata = ToolMetadata(
        name = "knowledge_search",
        description = "Semantic retrieval over the knowledge base: call this at the start of a task with " +
            "keywords extracted from the user's question to retrieve relevant documents. Issue it in the " +
            "SAME response as 'memory_search' (when available) so both run in parallel. Optionally filter " +
            "by 'source' or 'kcategory'.",
        permissionCategory = "knowledge"
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
