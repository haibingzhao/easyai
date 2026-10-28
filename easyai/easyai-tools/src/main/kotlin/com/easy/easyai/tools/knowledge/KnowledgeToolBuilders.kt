package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeFolderStore
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/** Static guidance for on-demand knowledge retrieval via knowledge_* tools (cache-stable). */
private const val KNOWLEDGE_GUIDANCE_SEGMENT = """
## Knowledge Base

You have access to a knowledge base via the knowledge_* tools. Knowledge content is NOT
included in this prompt to keep it stable for caching — retrieve it on demand instead.

At the START of each new task, proactively call `knowledge_search` with keywords extracted
from the user's request to retrieve relevant articles. When `memory_search` is also
available, you MUST issue both calls in the SAME response so they run in parallel.
Use `knowledge_read` to load the full content of a specific article by its id.
"""

@Component
class KnowledgeSearchToolBuilder(
    @Value("\${easyai.knowledge.shared-within-group:false}") sharedWithinGroup: Boolean,
    accessGuardProvider: ObjectProvider<KnowledgeAccessGuard>,
    articleStoreProvider: ObjectProvider<KnowledgeArticleStore>,
    folderStoreProvider: ObjectProvider<KnowledgeFolderStore>,
) : AbstractKnowledgeToolBuilder(
    sharedWithinGroup,
    accessGuardProvider,
    articleStoreProvider,
    folderStoreProvider,
) {
    override val metadata = ToolMetadata(
        name = "knowledge_search",
        description = "Semantic retrieval over the knowledge base: call this at the start of a task with " +
            "keywords extracted from the user's question to retrieve relevant articles. Issue it in the " +
            "SAME response as 'memory_search' (when available) so both run in parallel. Optionally narrow " +
            "results by 'kcategory' or by 'folder' (the folder display path, e.g. 'Family/Recipes').",
        permissionCategory = "knowledge",
        systemPromptSegment = KNOWLEDGE_GUIDANCE_SEGMENT.trimIndent(),
        promptSegmentOrder = 30
    )

    override fun createTool(
        store: KnowledgeStore,
        knowledgeOwnerId: String,
        accessGuard: KnowledgeAccessGuard,
        accessContext: KnowledgeAccessContext,
        articleStore: KnowledgeArticleStore?,
        folderStore: KnowledgeFolderStore?,
    ): ToolDefinition = KnowledgeSearchTool(
        metadata,
        store,
        knowledgeOwnerId,
        accessGuard,
        accessContext,
        articleStore,
        folderStore,
    )
}

@Component
class KnowledgeReadToolBuilder(
    @Value("\${easyai.knowledge.shared-within-group:false}") sharedWithinGroup: Boolean,
    accessGuardProvider: ObjectProvider<KnowledgeAccessGuard>,
    articleStoreProvider: ObjectProvider<KnowledgeArticleStore>,
    folderStoreProvider: ObjectProvider<KnowledgeFolderStore>,
) : AbstractKnowledgeToolBuilder(
    sharedWithinGroup,
    accessGuardProvider,
    articleStoreProvider,
    folderStoreProvider,
) {
    override val metadata = ToolMetadata(
        name = "knowledge_read",
        description = "Read the full content of a specific knowledge article by its id, as returned " +
            "by knowledge_search (only published, retrievable articles are indexed; a draft must be " +
            "read through knowledge_article). Ids are opaque and stable; use the title/path shown in " +
            "the search result to judge relevance.",
        permissionCategory = "knowledge"
    )

    override fun createTool(
        store: KnowledgeStore,
        knowledgeOwnerId: String,
        accessGuard: KnowledgeAccessGuard,
        accessContext: KnowledgeAccessContext,
        articleStore: KnowledgeArticleStore?,
        folderStore: KnowledgeFolderStore?,
    ): ToolDefinition = KnowledgeReadTool(metadata, store, knowledgeOwnerId, accessGuard, accessContext)
}
