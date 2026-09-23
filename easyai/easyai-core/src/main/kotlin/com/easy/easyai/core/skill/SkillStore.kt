package com.easy.easyai.core.skill

/** A discovery projection, never the local content or permission source of truth. */
interface SkillStore {
    /** Each input gets a result. Awaiting or accepting a write is not proof of readiness. */
    suspend fun submit(
        entries: List<SkillEntry>,
        scope: SkillScope,
        owner: SkillOwnerContext,
        awaitIndexing: Boolean = false
    ): List<SkillSubmitResult>

    /** Exact bizId + externalId lookup, not a semantic search. */
    suspend fun inspect(name: String, scope: SkillScope, owner: SkillOwnerContext): SkillDocumentState

    /** Idempotent: only confirmed absence succeeds; disabled/unavailable backends fail. */
    suspend fun ensureAbsent(name: String, scope: SkillScope, owner: SkillOwnerContext): SkillDeleteResult

    suspend fun search(
        query: String,
        scopes: List<SkillScope>,
        owner: SkillOwnerContext,
        topK: Int = 5
    ): List<SkillEntry>
}

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
