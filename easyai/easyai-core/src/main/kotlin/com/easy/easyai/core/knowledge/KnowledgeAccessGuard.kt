package com.easy.easyai.core.knowledge

/**
 * Single entry point for knowledge access checks.
 *
 * Article objects go straight to the policy (or through [KnowledgeArticleService] for the
 * article APIs); RAG entries (which may have no metadata row) are resolved against
 * [KnowledgeArticleStore] first so the policy sees the row when it exists. Web controllers and
 * agent tools use this class instead of re-implementing the lookup.
 */
class KnowledgeAccessGuard(
    private val policy: KnowledgeAccessPolicy,
    private val articleStore: KnowledgeArticleStore,
) {

    /** Object-level helpers for host call sites that already hold an article row. */
    suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean =
        policy.canRead(context, article)

    suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean =
        policy.canWrite(context, article)

    suspend fun canManage(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean =
        policy.canManage(context, article)

    suspend fun canReadEntry(
        context: KnowledgeAccessContext,
        ownerId: String,
        key: String,
    ): Boolean {
        val article = articleStore.findByKey(ownerId, key)
        return policy.canReadEntry(context, ownerId, key, article)
    }

    suspend fun canManageEntry(
        context: KnowledgeAccessContext,
        ownerId: String,
        key: String,
    ): Boolean {
        val article = articleStore.findByKey(ownerId, key)
        return policy.canManageEntry(context, ownerId, key, article)
    }

    /** Batch variant used by RAG list/search: returns the subset of [keys] the caller may read. */
    suspend fun filterReadableEntries(
        context: KnowledgeAccessContext,
        ownerId: String,
        keys: Collection<String>,
    ): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val byKey = articleStore.findByKeys(ownerId, keys).associateBy { it.knowledgeKey }
        return keys.filterTo(linkedSetOf()) { key ->
            policy.canReadEntry(context, ownerId, key, byKey[key])
        }
    }

    /** Batch variant used by source deletion. */
    suspend fun filterManageableEntries(
        context: KnowledgeAccessContext,
        ownerId: String,
        keys: Collection<String>,
    ): Set<String> {
        if (keys.isEmpty()) return emptySet()
        val byKey = articleStore.findByKeys(ownerId, keys).associateBy { it.knowledgeKey }
        return keys.filterTo(linkedSetOf()) { key ->
            policy.canManageEntry(context, ownerId, key, byKey[key])
        }
    }
}
