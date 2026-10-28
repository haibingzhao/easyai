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
 * Product-owned access policy for knowledge articles and folders.
 *
 * The framework owns authentication, owner-bucket resolution and RAG slicing; this SPI only
 * answers "may this caller see/create/edit this article or rearrange this folder". A host
 * (e.g. Easy Home) overrides the bean to plug its own authorization matrix, which is also what
 * enables group-wide sharing.
 */
interface KnowledgeAccessPolicy {

    suspend fun canView(context: KnowledgeAccessContext): Boolean = true

    suspend fun canCreate(context: KnowledgeAccessContext): Boolean = true

    /** Folder create/rename/delete. Defaults to the article-create right. */
    suspend fun canManageFolders(context: KnowledgeAccessContext): Boolean = canCreate(context)

    suspend fun canRead(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean

    suspend fun canWrite(context: KnowledgeAccessContext, article: KnowledgeArticle): Boolean

    suspend fun canManage(
        context: KnowledgeAccessContext,
        article: KnowledgeArticle,
    ): Boolean = canWrite(context, article)
}

/**
 * Default policy:
 * - rows stamped with `created_by` are readable/writable only by that login user;
 * - legacy rows without a creator fall back to owner-bucket membership, and in a shared group
 *   bucket to every member carrying that group.
 *
 * This preserves personal-bucket behaviour. It deliberately does NOT widen stamped rows to the
 * group, so `easyai.knowledge.shared-within-group=true` only changes the storage bucket; hosts
 * that want group-wide read/write must override this bean.
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
