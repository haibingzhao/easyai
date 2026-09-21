package com.easy.easyai.core.skill

/**
 * Retrieval-index store for skills (EasyRAG-backed in production).
 *
 * Sits at the same layer as [com.easy.easyai.core.memory.MemoryStore] and
 * [com.easy.easyai.core.knowledge.KnowledgeStore]: it indexes SKILL.md documents so
 * agents can *discover* skills semantically instead of having every name+description
 * stuffed into each system prompt. Execution always reads from disk — this store is
 * a discovery index, never the content source of truth for local skills.
 *
 * Tenant isolation is enforced at the storage layer via `biz_id` slices derived from
 * [SkillOwnerContext]; implementations must not rely on metadata filtering for that.
 *
 * Skill indexing is a **non-critical path**: implementations degrade to empty results
 * rather than propagating backend failures, so a RAG outage can never break the agent
 * loop or application startup.
 */
interface SkillStore {

    /**
     * Index entries into the slice for the given scope/owner (log-and-continue).
     * Idempotent: re-indexing unchanged content is a no-op server-side.
     *
     * @param awaitIndexing true blocks until the backend finished indexing, which is what the
     *   single-skill paths use so a skill is searchable the instant it exists; bulk startup
     *   reconciliation leaves it false and accepts second-level consistency
     * @return number of entries successfully submitted for indexing
     */
    suspend fun index(
        entries: List<SkillEntry>,
        scope: SkillScope,
        owner: SkillOwnerContext,
        awaitIndexing: Boolean = false
    ): Int

    /**
     * Search the slices for all requested scopes in a **single** backend round trip
     * (EasyRAG `bizIds` set recall), rather than one query per scope.
     *
     * @param scopes slices to query; when [SkillScope.PROJECT] is requested but the owner
     *   has no `projectPath`, that slice is silently dropped
     * @param topK per-slice result quota applied after merging (the backend returns one
     *   global top-k over the union, so clients over-fetch and re-quota)
     */
    suspend fun search(
        query: String,
        scopes: List<SkillScope>,
        owner: SkillOwnerContext,
        topK: Int = 5
    ): List<SkillEntry>

    /** Remove the index for one skill from a given slice. */
    suspend fun delete(name: String, scope: SkillScope, owner: SkillOwnerContext): Boolean
}
