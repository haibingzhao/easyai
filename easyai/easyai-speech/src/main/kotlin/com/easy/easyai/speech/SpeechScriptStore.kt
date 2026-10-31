package com.easy.easyai.speech

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * What a persisted script was spoken *from*.
 *
 * The set is closed on purpose: the API validates target keys it accepts, and an unknown value
 * already in the table is read back as data (never as a failure) so that adding a type cannot break
 * an older client that does not know it yet.
 */
enum class SpeechScriptTarget(val key: String) {
    /** One assistant reply; `targetId` is the message id and `sessionId` is set. */
    SESSION_MESSAGE("session_message"),

    /** Text the user typed or pasted; there is no target row, so `targetId` is null. */
    MANUAL_TEXT("manual_text"),

    /** A knowledge article body; wired in a later batch, listed here so the UI can label old rows. */
    KNOWLEDGE_ARTICLE("knowledge_article"),

    /** A tool call result (for example `generate_speech`); `sessionId` is set when there is one. */
    TOOL_CALL("tool_call");

    companion object {
        @JvmStatic
        fun fromKey(key: String?): SpeechScriptTarget? =
            entries.firstOrNull { it.key == key?.trim()?.lowercase() }
    }
}

/**
 * Lifecycle of a stored script.
 *
 * AUTO is the machine's copy: one per target, overwritten by the next plan for the same text, and
 * expired after a retention window. PINNED is the user's copy: never overwritten, never expired.
 * ARCHIVED is a soft delete — the row stays so that "this was deleted" is a fact, not an absence.
 */
enum class SpeechScriptStatus {
    AUTO,
    PINNED,
    ARCHIVED;

    companion object {
        @JvmStatic
        fun fromKey(key: String?): SpeechScriptStatus? =
            entries.firstOrNull { it.name.equals(key?.trim(), ignoreCase = true) }
    }
}

/** List ordering; every variant is a column, so no sort happens in memory. */
enum class SpeechScriptSort {
    UPDATED,
    LAST_PLAYED,
    PLAY_COUNT
}

/**
 * A stored script with everything needed to render it again.
 *
 * [script] is the plan itself (`segments_json`); [sourceText] is kept beside it because an edit must
 * be re-validated against the text the script was written for, never against text a client sends.
 */
data class SpeechScriptRecord(
    val id: String,
    val owner: String,
    val owners: List<String>,
    val targetType: String,
    val targetId: String?,
    val sessionId: String?,
    val title: String,
    val sourceText: String,
    val sourceDigest: String,
    val mode: String,
    val status: SpeechScriptStatus,
    val revision: Int,
    val script: SpeechScript,
    val ttsParams: SpeechTtsParams,
    val directionParams: SpeechDirectionParams?,
    val isFavorite: Boolean = false,
    val playCount: Int = 0,
    val lastPlayedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0
) {
    val sourceChars: Int get() = sourceText.length
    val spokenChars: Int get() = script.segments.sumOf { it.spokenText.length }
    val segmentCount: Int get() = script.segments.size
}

/** A list row: never carries [SpeechScriptRecord.script], so a page stays small. */
data class SpeechScriptSummary(
    val id: String,
    val title: String,
    val targetType: String,
    val targetId: String?,
    val sessionId: String?,
    val mode: String,
    val status: SpeechScriptStatus,
    val revision: Int,
    val segmentCount: Int,
    val sourceChars: Int,
    val spokenChars: Int,
    val style: String,
    val mood: String?,
    val isFavorite: Boolean,
    val playCount: Int,
    val lastPlayedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

/**
 * List filter.
 *
 * [search] matches the title or the source text head; the store applies it as a `LIKE`, which is
 * enough for a personal library and avoids a second index for a feature nobody will paginate past.
 */
data class SpeechScriptQuery(
    val targetType: String? = null,
    val targetId: String? = null,
    val sessionId: String? = null,
    val status: SpeechScriptStatus? = null,
    val mode: String? = null,
    val favoriteOnly: Boolean = false,
    val search: String? = null,
    val limit: Int = 50,
    val offset: Int = 0,
    val sort: SpeechScriptSort = SpeechScriptSort.UPDATED
)

/**
 * Storage for persisted read-aloud scripts.
 *
 * Every method is owner-scoped: an id that exists but belongs to someone else must read as absent,
 * not as a permission error, so a guessed id leaks nothing.
 *
 * The AUTO contract is enforced here rather than by a unique index (H2 has no filtered index):
 * [saveAuto] replaces the single AUTO row for its target, [savePinned] never does.
 */
interface SpeechScriptStore {

    /** Insert or replace the machine-saved script for this target, keeping its id stable. */
    suspend fun saveAuto(record: SpeechScriptRecord): SpeechScriptRecord

    /** Insert a user-saved script; an existing PINNED row is never replaced. */
    suspend fun savePinned(record: SpeechScriptRecord): SpeechScriptRecord

    /**
     * The id [saveAuto] would keep for this target, or null when no AUTO row exists yet.
     *
     * A render that has not been planned by the panel yet needs this: [saveAuto] keeps an existing
     * row's id, so emitting a fresh random id in `meta` would leave the archive, the plan cache and
     * `touchPlayed` pointing at different rows.
     */
    suspend fun findAutoId(owner: String, targetType: String, targetId: String?): String?

    /**
     * The newest reusable script for a target whose source digest still matches.
     *
     * Returns null when the text changed: a script written for an older reply must not be spoken
     * over a regenerated one.
     */
    suspend fun findReusable(
        owner: String,
        targetType: String,
        targetId: String?,
        digest: String
    ): SpeechScriptRecord?

    suspend fun get(owner: String, id: String): SpeechScriptRecord?

    suspend fun list(owner: String, query: SpeechScriptQuery): List<SpeechScriptSummary>

    /**
     * Replace a stored script only if it is still at [expectedRevision].
     *
     * Two tabs (or a panel and a list page) can both read revision 3 and both submit an edit;
     * without this comparison the later write would silently discard the earlier one's segments.
     */
    suspend fun update(record: SpeechScriptRecord, expectedRevision: Int): Boolean

    /** Soft delete: the row keeps its history, the list stops returning it. */
    suspend fun archive(owner: String, id: String): Boolean

    /** Record a playback without touching the script body. */
    suspend fun touchPlayed(owner: String, id: String, at: Long)

    /** Archive every AUTO script older than [before]; returns how many rows moved. */
    suspend fun purgeAutoBefore(owner: String, before: Long): Int

    /** Archive everything pointing at a deleted target (a session or message going away). */
    suspend fun archiveForTarget(targetType: String, targetId: String): Int

    /**
     * Archive every script captured from [sessionId], whatever message it came from.
     *
     * [archiveForTarget] cannot serve this: a script's target id is the message, so the only column
     * that covers a whole conversation is `session_id`. Not owner-scoped — the caller has already
     * authorised the session itself, and a session's scripts may be shared with more than one owner.
     */
    suspend fun archiveForSession(sessionId: String): Int
}

/**
 * Snapshot of the parameters that produced a script's audio.
 *
 * A snapshot, not a reference: model rows get renamed, retyped, re-pointed and deleted, so a join
 * could not answer "what did this sound like and why" six months later. Credentials are excluded by
 * construction — [SpeechProviderParams] has no field for an API key, and [SpeechProviderParams.baseUrlHost]
 * is the host only.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechTtsParams(
    val schemaVersion: Int = 1,
    val capturedAt: Long,
    /** `direct` / `refine` / `director` — which pipeline produced this script. */
    val mode: String,
    val provider: SpeechProviderParams,
    val synthesis: SpeechSynthesisParams,
    val rowDefaults: SpeechRowDefaults? = null,
    val overrides: SpeechOverrideParams = SpeechOverrideParams(),
    val perSegment: List<SpeechSegmentParams> = emptyList(),
    val cost: SpeechCostParams
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechProviderParams(
    val modelConfigId: String,
    val modelId: String,
    val modelDisplayName: String? = null,
    val protocol: String,
    /** Host only (for example `dashscope.aliyuncs.com`): enough to spot a provider change. */
    val baseUrlHost: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechSynthesisParams(
    val format: String,
    val mimeType: String,
    val sampleRate: Int? = null,
    val bitRate: Int? = null,
    val inputMode: String,
    val timeoutSeconds: Long
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechRowDefaults(
    val voice: String? = null,
    val wordTimestampEnabled: Boolean? = null,
    /** The row's `media_options` verbatim: new keys must not need a schema change to be recorded. */
    val options: Map<String, Any?> = emptyMap()
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechOverrideParams(
    val voice: String? = null,
    val instruction: String? = null,
    val emotionTag: String? = null,
    val rate: Double? = null,
    val pitch: Double? = null
)

/** Per-segment controls, flattened so the detail page can chart voices without parsing the script. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechSegmentParams(
    val segmentId: String,
    val voice: String,
    val emotion: String? = null,
    val rate: Double? = null,
    val role: String,
    val pauseAfterMs: Int,
    val sourceChars: Int,
    val spokenChars: Int
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechCostParams(
    val maxChars: Int,
    val characters: Int
)

/**
 * Snapshot of the planning model, when a plan was directed rather than read directly.
 *
 * [promptVersion] and [promptHash] exist because the planner's instructions are code: without them,
 * "the same reply reads differently today" has no explanation.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SpeechDirectionParams(
    val modelConfigId: String,
    val modelId: String,
    val protocol: String,
    val samplingIntent: String,
    val thinking: Boolean,
    /** `complete` (whole plan) or `incremental` (streamed NDJSON). */
    val strategy: String,
    val promptVersion: Int,
    val promptHash: String,
    val schemaVersion: Int,
    val timeoutMs: Long
)
