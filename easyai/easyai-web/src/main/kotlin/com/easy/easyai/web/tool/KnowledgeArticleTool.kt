package com.easy.easyai.web.tool

import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.agent.AgentContext
import com.easy.easyai.core.agent.AgentService
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
        val key: String? = null,
        val title: String? = null,
        val content: String? = null,
        val category: String? = null,
        val source: String? = null,
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
                    val articles = service.listAccessible(
                        accessContext,
                        source = stringArg(args, "source"),
                        category = stringArg(args, "category"),
                    )
                    val page = articles.drop(offset).take(limit)
                    jsonResult(
                        mapOf(
                            "articles" to page.map { item ->
                                mapOf(
                                    "key" to item.article.knowledgeKey,
                                    "title" to item.article.title,
                                    "category" to item.article.category,
                                    "source" to item.article.source,
                                    "updatedAt" to item.article.updatedAt,
                                    "canWrite" to item.canWrite,
                                    "canManage" to item.canManage,
                                )
                            },
                            "total" to articles.size,
                            "offset" to offset,
                            "limit" to limit,
                        )
                    )
                }

                "read" -> {
                    val key = stringArg(args, "key")
                        ?: return errorResult(toolCallId, name, "read requires 'key'")
                    val content = service.readContent(accessContext, key)
                        ?: return errorResult(toolCallId, name, "Article not found or access denied: $key")
                    jsonResult(mapOf("key" to key, "content" to content))
                }

                "create" -> {
                    val title = stringArg(args, "title")
                        ?: return errorResult(toolCallId, name, "create requires 'title'")
                    val content = args["content"] as? String
                        ?: return errorResult(toolCallId, name, "create requires 'content'")
                    val article = service.saveArticle(
                        context = accessContext,
                        title = title,
                        content = content,
                        source = stringArg(args, "source") ?: "assistant",
                        relativePath = null,
                        category = stringArg(args, "category") ?: "other",
                        origin = "assistant",
                    )
                    jsonResult(mapOf("created" to true, "article" to articleToMap(article)))
                }

                "update" -> {
                    val key = stringArg(args, "key")
                        ?: return errorResult(toolCallId, name, "update requires 'key'")
                    val content = args["content"] as? String
                        ?: return errorResult(toolCallId, name, "update requires 'content'")
                    val article = service.updateArticle(
                        context = accessContext,
                        key = key,
                        content = content,
                        title = stringArg(args, "title"),
                        category = stringArg(args, "category"),
                    )
                    jsonResult(mapOf("updated" to true, "article" to articleToMap(article)))
                }

                "delete" -> {
                    val key = stringArg(args, "key")
                        ?: return errorResult(toolCallId, name, "delete requires 'key'")
                    val deleted = service.deleteArticle(accessContext, key)
                    jsonResult(mapOf("deleted" to deleted, "key" to key))
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

    private fun articleToMap(article: KnowledgeArticle): Map<String, Any?> = mapOf(
        "key" to article.knowledgeKey,
        "title" to article.title,
        "category" to article.category,
        "source" to article.source,
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
action: list (readable articles; optional source/category filters plus limit/offset) / read (full content by key) / create (new article; requires title and content, optional category and source) / update (update content by key; optional title and category) / delete (by key).
Access is decided by the host application's policy; unauthorized actions fail, so do not try to bypass them.
list returns at most 50 articles by default (max 200) and reports total/offset/limit.""",
        permissionCategory = "knowledge",
        isDefaultTool = false,
        patternKeys = listOf("key", "source"),
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
