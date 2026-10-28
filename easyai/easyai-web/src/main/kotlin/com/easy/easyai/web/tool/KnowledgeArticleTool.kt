package com.easy.easyai.web.tool

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
import com.easy.easyai.core.knowledge.ArticleStatus
import com.easy.easyai.core.knowledge.KnowledgeAccessContext
import com.easy.easyai.core.knowledge.KnowledgeArticle
import com.easy.easyai.core.knowledge.KnowledgeOwnership
import com.easy.easyai.core.model.TextContent
import com.easy.easyai.core.permission.PermissionAction
import com.easy.easyai.core.permission.PermissionRule
import com.easy.easyai.core.tool.BaseToolDefinition
import com.easy.easyai.core.tool.ToolBuilder
import com.easy.easyai.core.tool.ToolDefinition
import com.easy.easyai.core.tool.ToolMetadata
import com.easy.easyai.core.tool.ToolResult
import com.easy.easyai.core.tool.ToolUpdate
import com.easy.easyai.web.service.KnowledgeArticleService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Component

/**
 * Agent-facing CRUD over knowledge articles.
 *
 * Articles are addressed by their opaque, stable id. The folder path is a display/placement
 * concern only: moving or renaming folders never changes the id or the search index.
 *
 * The tool resolves the caller's access context from [AgentContext] and delegates every
 * decision to [KnowledgeArticleService], which consults the host KnowledgeAccessPolicy.
 */
class KnowledgeArticleTool(
    metadata: ToolMetadata,
    private val service: KnowledgeArticleService,
    private val accessContext: KnowledgeAccessContext,
) : BaseToolDefinition(metadata) {

    data class Parameters(
        val action: String,
        val id: String? = null,
        val title: String? = null,
        val content: String? = null,
        val category: String? = null,
        /** Folder display path, e.g. `Family/Recipes`. */
        val folderPath: String? = null,
        val status: String? = null,
        val limit: Int? = null,
        val offset: Int? = null,
    )

    override fun parameterType() = Parameters::class.java

    override suspend fun doExecute(
        agentContext: AgentContext,
        toolCallId: String,
        messageId: String?,
        args: Map<String, Any?>,
        coroutineScope: CoroutineScope,
        onUpdate: suspend (ToolUpdate) -> Unit
    ): ToolResult {
        val action = (args["action"] as? String)?.lowercase()?.trim()
            ?: return errorResult(
                toolCallId,
                name,
                "Missing required parameter: action (list/read/create/update/delete)"
            )

        return try {
            when (action) {
                "list" -> {
                    val limit = intArg(args, "limit", DEFAULT_LIST_LIMIT).coerceIn(1, MAX_LIST_LIMIT)
                    val offset = intArg(args, "offset", 0).coerceAtLeast(0)
                    val statusArg = stringArg(args, "status")
                    val statusFilter = ArticleStatus.fromFilter(statusArg)
                    if (statusArg != null && statusFilter == null) {
                        return errorResult(
                            toolCallId,
                            name,
                            "Invalid status filter: $statusArg (expected draft or published)"
                        )
                    }
                    val articles = service.listAccessible(
                        accessContext,
                        category = stringArg(args, "category"),
                        status = statusFilter,
                    )
                    val folderScope = stringArg(args, "folderPath")
                    val filtered = if (folderScope == null) {
                        articles
                    } else {
                        val prefix = folderScope.trimEnd('/')
                        articles.filter {
                            it.folderPath == prefix || it.folderPath.startsWith("$prefix/")
                        }
                    }
                    val page = filtered.drop(offset).take(limit)
                    jsonResult(
                        mapOf(
                            "articles" to page.map { item ->
                                mapOf(
                                    "id" to item.article.id,
                                    "title" to item.article.title,
                                    "path" to item.folderPath,
                                    "category" to item.article.category,
                                    "status" to ArticleStatus.toWire(item.article.status),
                                    "ragEnabled" to item.article.ragEnabled,
                                    "updatedAt" to item.article.updatedAt,
                                    "canWrite" to item.canWrite,
                                    "canManage" to item.canManage,
                                )
                            },
                            "total" to filtered.size,
                            "offset" to offset,
                            "limit" to limit,
                        )
                    )
                }

                "read" -> {
                    val id = stringArg(args, "id")
                        ?: return errorResult(toolCallId, name, "read requires 'id'")
                    val content = service.readContent(accessContext, id)
                        ?: return errorResult(toolCallId, name, "Article not found or access denied: $id")
                    jsonResult(mapOf("id" to id, "content" to content))
                }

                "create" -> {
                    val title = stringArg(args, "title")
                        ?: return errorResult(toolCallId, name, "create requires 'title'")
                    val content = args["content"] as? String
                        ?: return errorResult(toolCallId, name, "create requires 'content'")
                    val folderPath = stringArg(args, "folderPath")
                    val folderId = folderPath?.let { path ->
                        service.resolveFolderIdByPath(accessContext, path)
                            ?: return errorResult(toolCallId, name, "Folder not found: $path")
                    }
                    val article = service.saveArticle(
                        context = accessContext,
                        title = title,
                        content = content,
                        folderId = folderId,
                        category = stringArg(args, "category") ?: "other",
                        origin = "assistant",
                    )
                    jsonResult(
                        mapOf(
                            "created" to true,
                            "article" to articleToMap(article, service.folderPathOf(article)),
                        )
                    )
                }

                "update" -> {
                    val id = stringArg(args, "id")
                        ?: return errorResult(toolCallId, name, "update requires 'id'")
                    val content = args["content"] as? String
                        ?: return errorResult(toolCallId, name, "update requires 'content'")
                    val result = service.updateArticle(
                        context = accessContext,
                        id = id,
                        content = content,
                        title = stringArg(args, "title"),
                        category = stringArg(args, "category"),
                    )
                    jsonResult(
                        mapOf(
                            "updated" to true,
                            "indexPending" to result.indexPending,
                            "article" to articleToMap(result.article, service.folderPathOf(result.article)),
                        )
                    )
                }

                "delete" -> {
                    val id = stringArg(args, "id")
                        ?: return errorResult(toolCallId, name, "delete requires 'id'")
                    val deleted = service.deleteArticle(accessContext, id)
                    jsonResult(mapOf("deleted" to deleted, "id" to id))
                }

                else -> errorResult(
                    toolCallId,
                    name,
                    "Unknown action: $action (expected list/read/create/update/delete)"
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            errorResult(toolCallId, name, "Operation failed: ${e.message}")
        }
    }

    private fun articleToMap(article: KnowledgeArticle, folderPath: String): Map<String, Any?> = mapOf(
        "id" to article.id,
        "title" to article.title,
        "path" to folderPath,
        "category" to article.category,
        "status" to ArticleStatus.toWire(article.status),
        "ragEnabled" to article.ragEnabled,
        "updatedAt" to article.updatedAt,
    )

    private fun jsonResult(payload: Any): ToolResult =
        ToolResult(content = listOf(TextContent(mapper.writeValueAsString(payload))))

    private companion object {
        private val mapper = SharedObjectMapper.instance
        private const val DEFAULT_LIST_LIMIT = 50
        private const val MAX_LIST_LIMIT = 200

        private fun stringArg(args: Map<String, Any?>, key: String): String? =
            (args[key] as? String)?.trim()?.ifBlank { null }

        private fun intArg(args: Map<String, Any?>, key: String, default: Int): Int =
            (args[key] as? Number)?.toInt()
                ?: (args[key] as? String)?.toIntOrNull()
                ?: default
    }
}

@Component
class KnowledgeArticleToolBuilder(
    // Lazy: the tool registry eagerly collects builders; the article service touches storage/RAG
    // only when a tool actually executes.
    @param:Lazy private val service: KnowledgeArticleService,
) : ToolBuilder {

    override val metadata = ToolMetadata(
        name = "knowledge_article",
        description = """Manage knowledge base articles (the single entry point for family and personal articles).
action: list (readable articles; optional category/status/folderPath filters plus limit/offset) / read (full content by id) / create (new DRAFT article; requires title and content, optional category and folderPath) / update (content by id; optional title and category) / delete (by id).
Ids are opaque and stable (UUIDs): always address an article by the 'id' returned from list or knowledge_search. 'path' (e.g. 'Family/Recipes') is display and placement only — renaming or moving folders never changes the id.
Access is decided by the host application's policy; unauthorized actions fail, so do not try to bypass them.
Status: 'draft' articles are private to their creator and are NOT in the search index; only 'published' articles with ragEnabled=true are retrievable via knowledge_search. An article created here stays a draft until the user publishes it in the UI — do not claim it is searchable.
list returns at most 50 articles by default (max 200), includes status and ragEnabled, and reports total/offset/limit.""",
        permissionCategory = "knowledge",
        isDefaultTool = false,
        patternKeys = listOf("id", "folderPath"),
    )

    override suspend fun build(context: AgentContext, agentService: AgentService): ToolDefinition? {
        val userId = context.userId?.takeIf { it.isNotBlank() } ?: return null
        val groupUserId = KnowledgeOwnership.groupBucket(userId, context.owners)
        val accessContext = KnowledgeAccessContext(
            userId = userId,
            groupUserId = groupUserId,
            owners = context.owners,
        )
        return KnowledgeArticleTool(metadata, service, accessContext)
    }

    override val defaultPermissionRules = listOf(
        PermissionRule("tool.execute.knowledge", "*", PermissionAction.ALLOW)
    )
}
