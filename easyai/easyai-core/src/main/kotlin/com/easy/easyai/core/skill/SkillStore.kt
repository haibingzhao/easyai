package com.easy.easyai.core.skill

/** A discovery projection, never the local content or permission source of truth. */
interface SkillStore {
    /** Each input gets a result. Awaiting or accepting a write is not proof of readiness. */
    suspend fun submit(
        entries: List<SkillEntry>,
        owner: SkillOwnerContext,
        awaitIndexing: Boolean = false
    ): List<SkillSubmitResult>

    /** Exact bizId + externalId lookup, not a semantic search. */
    suspend fun inspect(name: String, owner: SkillOwnerContext): SkillDocumentState

    /** Idempotent: only confirmed absence succeeds; disabled/unavailable backends fail. */
    suspend fun ensureAbsent(name: String, owner: SkillOwnerContext): SkillDeleteResult

    /**
     * Searches one biz_id slice per owner id. Result ordering/merging (e.g. a user's own skill
     * shadowing a same-named shared skill) is the caller's responsibility.
     */
    suspend fun search(
        query: String,
        ownerUserIds: List<String>,
        topK: Int = 5
    ): List<SkillEntry>
}

/**
 * Ownership context for skill operations, used to derive backend-level isolation
 * (EasyRAG biz_id slices `u_{userId}_s`). The `system` owner is the shared read-only layer.
 *
 * @param userId Owner user id as recorded in the `skill` catalog table; null or blank
 *   falls back to the `system` tenant (single-user/dev/desktop degradation).
 */
data class SkillOwnerContext(
    val userId: String? = null
)

data class SkillSubmitResult(val key: String, val state: SkillDocumentState)

sealed interface SkillDocumentState {
    data object Absent : SkillDocumentState
    data class Submitted(val checksum: String?) : SkillDocumentState
    data class Processed(val checksum: String?) : SkillDocumentState
    data class Failed(val error: String) : SkillDocumentState
}

sealed interface SkillDeleteResult {
    data object Absent : SkillDeleteResult
    data class Failed(val error: String) : SkillDeleteResult
}
