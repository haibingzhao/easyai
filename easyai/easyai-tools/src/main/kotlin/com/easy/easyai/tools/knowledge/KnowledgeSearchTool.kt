package com.easy.easyai.tools.knowledge

import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeAccessGuard
import com.easy.easyai.core.knowledge.KnowledgeArticleStore
import com.easy.easyai.core.knowledge.KnowledgeFolderStore
import com.easy.easyai.core.knowledge.KnowledgeStore
import com.easy.easyai.core.knowledge.KnowledgeTree
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import kotlinx.coroutines.CoroutineScope

internal class KnowledgeSearchTool(
    metadata: ToolMetadata,
    private val store: KnowledgeStore,
    private val knowledgeOwnerId: String,
    private val accessGuard: KnowledgeAccessGuard,
    private val accessContext: KnowledgeAccessContext,
    private val articleStore: KnowledgeArticleStore?,
    private val folderStore: KnowledgeFolderStore?,
) : BaseToolDefinition(metadata) {

    data class Parameters(
        val query: String,
        val kcategory: String? = null,
        /** Folder display path, e.g. `Family/Recipes`. */
        val folder: String? = null
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
        val query = (args["query"] as? String)?.trim()
        if (query.isNullOrBlank()) {
            return errorResult("Error: 'query' parameter is required and must not be empty.")
        }
        val kcategory = (args["kcategory"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        val folderPath = (args["folder"] as? String)?.trim()?.takeIf { it.isNotBlank() }
        if (folderPath != null && folderStore == null) {
            return textResult("Folder filtering is unavailable in this installation.")
        }

        // Filters are applied after retrieval, so over-fetch to keep the requested result count.
        val topK = if (kcategory != null || folderPath != null) {
            KnowledgeStore.DEFAULT_TOP_K * 2
        } else {
            KnowledgeStore.DEFAULT_TOP_K
        }
        val entries = store.search(knowledgeOwnerId, query, topK)
        if (entries.isEmpty()) {
            return textResult("No knowledge entries found matching '$query'.")
        }

        val readable = accessGuard.filterReadableEntries(
            accessContext,
            knowledgeOwnerId,
            entries.map { it.id },
        )
        val rows = articleStore?.findByIds(knowledgeOwnerId, entries.map { it.id })?.associateBy { it.id }
        val folders = folderStore?.listByBuckets(listOf(knowledgeOwnerId)).orEmpty()
        val foldersById = folders.associateBy { it.id }

        val folderScope = if (folderPath != null) {
            val folder = KnowledgeTree.resolve(knowledgeOwnerId, folderPath, folders)
                ?: return textResult("No knowledge folder found matching '$folderPath'.")
            KnowledgeTree.subtreeIds(folder.id, folders)
        } else {
            null
        }

        val hits = entries.asSequence()
            .filter { it.id in readable }
            .mapNotNull { entry ->
                val row = rows?.get(entry.id)
                // When metadata is available, an index hit without a row is not an article.
                if (rows != null && row == null) return@mapNotNull null
                if (folderScope != null) {
                    if (row == null || row.folderId !in folderScope) return@mapNotNull null
                }
                val category = row?.category ?: entry.kcategory
                if (kcategory != null && category != kcategory) return@mapNotNull null
                Hit(
                    id = entry.id,
                    title = row?.title ?: entry.title,
                    path = row?.let { KnowledgeTree.pathOf(it.folderId, foldersById) } ?: "",
                    category = category,
                    preview = entry.content.take(CONTENT_PREVIEW_LIMIT),
                )
            }
            .take(KnowledgeStore.DEFAULT_TOP_K)
            .toList()

        if (hits.isEmpty()) {
            return textResult("No knowledge entries found matching '$query'.")
        }

        val text = buildString {
            appendLine("Found ${hits.size} knowledge entries:")
            hits.forEach { hit ->
                appendLine()
                appendLine("[id: ${hit.id}] ${hit.title}")
                if (hit.path.isNotBlank()) appendLine("path: ${hit.path}")
                if (hit.category.isNotBlank()) appendLine("category: ${hit.category}")
                appendLine(hit.preview)
            }
        }
        return textResult(text.trimEnd())
    }

    private fun textResult(text: String): ToolResult =
        ToolResult(content = listOf(TextContent(text)))

    private data class Hit(
        val id: String,
        val title: String,
        val path: String,
        val category: String,
        val preview: String,
    )

    private companion object {
        /** Max characters of entry content returned per search hit, reducing follow-up knowledge_read calls. */
        const val CONTENT_PREVIEW_LIMIT = 1500
    }
}
