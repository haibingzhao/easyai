package com.easy.easyai.core.knowledge

/**
 * Identity carried into every knowledge access decision.
 *
 * The framework only knows the login user, the optional group bucket and the ordered owner
 * set; product-level roles (family member, household owner, ...) stay in the host policy.
 */
data class KnowledgeAccessContext(
    val userId: String,
    val groupUserId: String? = null,
    val owners: Set<String> = emptySet(),
)

/**
 * Product-owned access policy for knowledge articles and their RAG entries.
 *
 * The framework still owns authentication, owner-bucket resolution and RAG slicing; this SPI
 * only answers "may this caller see/edit this article". [DefaultKnowledgeAccessPolicy] keeps
 * the pre-policy behaviour for personal buckets and narrows creator-stamped rows to their
 * creator; a host (e.g. Easy Home) overrides the bean to plug its own authorization matrix,
 * which is also what enables group-wide sharing.
 */
interface KnowledgeAccessPolicy {

    suspend fun canView(context: KnowledgeAccessContext): Boolean = true

    suspend fun canCreate(context: KnowledgeAccessContext): Boolean = true

    suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean

    suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean

    suspend fun canManage(
        context: KnowledgeAccessContext,
        article: KnowledgeArticle,
    ): Boolean = canWrite(context, article)

    /**
     * Default batch helper for host call sites. Framework list paths use
     * [KnowledgeAccessGuard.filterReadableEntries], which resolves article metadata first.
     */
    suspend fun filterReadable(
        context: KnowledgeAccessContext,
        articles: List<KnowledgeArticle>,
    ): List<KnowledgeArticle> = articles.filter { canRead(context, it) }

    /**
     * Access check for a RAG entry that may not have an article metadata row.
     * Default is permissive (legacy rows without metadata stay reachable). Hosts that require
     * metadata for access override this to deny — which is also what makes
     * [KnowledgeArticleStore.deleteByCreatedBy] revoke access to the content and RAG entry.
     */
    suspend fun canReadEntry(
        context: KnowledgeAccessContext,
        ownerId: String,
        key: String,
        article: KnowledgeArticle?,
    ): Boolean = article?.let { canRead(context, it) } ?: true

    suspend fun canManageEntry(
        context: KnowledgeAccessContext,
        ownerId: String,
        key: String,
        article: KnowledgeArticle?,
    ): Boolean = article?.let { canManage(context, it) } ?: true
}

/**
 * Default policy:
 * - rows stamped with `created_by` are readable/writable only by that login user;
 * - legacy rows without a creator fall back to owner-bucket membership, and in a shared group
 *   bucket to every member carrying that group.
 *
 * This preserves the pre-policy behaviour for personal buckets. It deliberately does NOT widen
 * stamped rows to the group, so `easyai.knowledge.shared-within-group=true` only changes the
 * storage bucket; hosts that want group-wide read/write must override this bean.
 */
class DefaultKnowledgeAccessPolicy(
    private val sharedWithinGroup: Boolean = false,
) : KnowledgeAccessPolicy {

    override suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean {
        val createdBy = article.createdBy
        if (createdBy != null) return createdBy == context.userId
        val sameBucket = article.userId == context.userId
        val sharedBucket = sharedWithinGroup &&
            context.groupUserId != null &&
            article.userId == context.groupUserId
        return sameBucket || sharedBucket
    }

    override suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean =
        canRead(context, article)
}
