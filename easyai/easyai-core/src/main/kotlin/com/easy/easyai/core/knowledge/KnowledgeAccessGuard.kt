package com.easy.easyai.core.knowledge

/**
 * Single entry point for index-level knowledge access checks.
 *
 * The retrieval index only knows article ids; the guard resolves the metadata row inside the
 * searched bucket and asks the host [KnowledgeAccessPolicy]. An id without a row is denied:
 * after the article/index merge there is no such thing as a retrievable document without
 * metadata.
 */
class KnowledgeAccessGuard(
    private val policy: KnowledgeAccessPolicy,
    private val articleStore: KnowledgeArticleStore,
) {

    suspend fun canReadEntry(
        context: KnowledgeAccessContext,
        ownerId: String,
        articleId: String,
    ): Boolean {
        val article = articleStore.findById(articleId) ?: return false
        if (article.userId != ownerId) return false
        if (!article.isRetrievable()) return false
        return policy.canRead(context, article)
    }

    /** Batch variant used by RAG search: returns the subset of [ids] the caller may read. */
    suspend fun filterReadableEntries(
        context: KnowledgeAccessContext,
        ownerId: String,
        ids: Collection<String>,
    ): Set<String> {
        if (ids.isEmpty()) return emptySet()
        val byId = articleStore.findByIds(ownerId, ids).associateBy { it.id }
        return ids.filterTo(linkedSetOf()) { id ->
            // findByIds is already bucket-scoped; re-checking the bucket keeps the batch path
            // as strict as the single-id lookup if a store ever widens its query.
            byId[id]?.takeIf { it.userId == ownerId && it.isRetrievable() }
                ?.let { policy.canRead(context, it) } ?: false
        }
    }

    /** A stale index entry never makes a draft or a retrieval-disabled article readable. */
    private fun KnowledgeArticle.isRetrievable(): Boolean =
        status == ArticleStatus.PUBLISHED && ragEnabled
}
