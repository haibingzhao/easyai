package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.common.util.SharedObjectMapper
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.SpeechCostParams
import com.easy.easyai.speech.SpeechDirectionParams
import com.easy.easyai.speech.SpeechOverrideParams
import com.easy.easyai.speech.SpeechProviderParams
import com.easy.easyai.speech.SpeechRowDefaults
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechScriptQuery
import com.easy.easyai.speech.SpeechScriptRecord
import com.easy.easyai.speech.SpeechScriptStatus
import com.easy.easyai.speech.SpeechScriptStore
import com.easy.easyai.speech.SpeechScriptSummary
import com.easy.easyai.speech.SpeechScriptTarget
import com.easy.easyai.speech.SpeechSegmentParams
import com.easy.easyai.speech.SpeechSynthesisParams
import com.easy.easyai.speech.SpeechTtsParams
import com.easy.easyai.speech.SpokenTextNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service
import java.net.URI
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import tools.jackson.core.type.TypeReference

/**
 * What a script is being written for, resolved once per request.
 *
 * [targetId] is null for [SpeechScriptTarget.MANUAL_TEXT]; every other type must name its row.
 */
data class SpeechTarget(
    val type: String,
    val id: String? = null,
    val sessionId: String? = null
) {
    companion object {
        /** The default when a caller sends text without naming where it came from. */
        @JvmStatic
        fun manual(): SpeechTarget = SpeechTarget(SpeechScriptTarget.MANUAL_TEXT.key)
    }
}

/**
 * Everything needed to record how a script was produced.
 *
 * Built from the same `Session` the renderer uses, so the snapshot cannot describe a different
 * model than the one that actually spoke.
 */
data class SpeechCapture(
    val owner: String,
    val owners: List<String>,
    val target: SpeechTarget,
    val mode: String,
    val modelConfig: ModelProviderConfig,
    val settings: MediaProviderSettings,
    val format: String,
    val mimeType: String,
    val sampleRate: Int?,
    val maxChars: Int,
    val overrides: SpeechOverrideParams = SpeechOverrideParams()
)

/**
 * Persistence for read-aloud scripts: the seam between the read-aloud pipeline and `speech_script`.
 *
 * Two responsibilities, deliberately kept apart from [TtsService]:
 *  - **snapshotting** — turning a live model row into an immutable record of what was asked of it;
 *  - **lookup** — deciding whether a stored script may be spoken again for this text.
 *
 * The store is optional. Without one (no R2DBC, or a host that never configured the bean) every
 * method degrades to "nothing saved", which is exactly the previous behaviour: read-aloud keeps
 * working on the in-memory plan only.
 *
 * Fidelity rule: a script is reusable only while its `sourceDigest` still matches the text about to
 * be spoken. Skipping that would let a regenerated reply be read from the script written for its
 * predecessor.
 */
@Service
class SpeechScriptArchiveService(
    @param:Autowired(required = false)
    private val store: SpeechScriptStore? = null,
    @param:Autowired(required = false)
    private val auxModelResolver: AuxModelResolver? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** Last opportunistic expiry sweep, so a busy server does not run one per saved script. */
    private val lastPurgeAt = AtomicLong(0)

    /** False when no store is wired: callers can skip building snapshots entirely. */
    val available: Boolean get() = store != null

    /**
     * The script that may be reused for [text], or null.
     *
     * Only the digest is checked here; the caller still runs `SpeechScriptValidator.validateFull`,
     * because voice availability and length belong to the current model, not to the archive.
     */
    suspend fun reusable(capture: SpeechCapture, text: String): SpeechScriptRecord? {
        val store = store ?: return null
        val digest = SpokenTextNormalizer.sourceDigest(text)
        return store.findReusable(capture.owner, capture.target.type, capture.target.id, digest)
    }

    suspend fun load(owner: String, id: String): SpeechScriptRecord? = store?.get(owner, id)

    /**
     * The id [saveAuto] will keep for this target, so a fresh render can announce it up front.
     *
     * Best-effort like every other archive lookup: a database hiccup must not make a read-aloud
     * impossible. Losing the id only costs the play counter and edit persistence, never the audio.
     */
    suspend fun autoIdFor(capture: SpeechCapture): String? = try {
        store?.findAutoId(capture.owner, capture.target.type, capture.target.id)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        logger.warn("Could not look up the archived script for target '{}': {}", capture.target.type, e.message)
        null
    }

    suspend fun list(owner: String, query: SpeechScriptQuery): List<SpeechScriptSummary> =
        store?.list(owner, query).orEmpty()

    suspend fun archive(owner: String, id: String): Boolean = store?.archive(owner, id) ?: false

    /**
     * Best-effort play bookkeeping: a failed counter update must never turn a fully delivered read
     * into a failed stream.
     */
    suspend fun touchPlayed(owner: String, id: String) = withContext(NonCancellable) {
        val store = store ?: return@withContext
        try {
            store.touchPlayed(owner, id, System.currentTimeMillis())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not record a read of speech script '{}': {}", id, e.message)
        }
    }

    /** Machine-saved copy: one per target, replaced on every plan for the same text. */
    suspend fun saveAuto(
        capture: SpeechCapture,
        text: String,
        script: SpeechScript,
        incremental: Boolean
    ): SpeechScriptRecord? = withContext(NonCancellable) {
        // Playback and persistence have different lifetimes: closing the browser cancels the audio
        // stream, but the small UPSERT that has already started must still commit. Otherwise the
        // client sees a successful TTS exchange while `speech_script` remains empty.
        val store = store ?: return@withContext null
        val stored = try {
            store.saveAuto(recordOf(capture, text, script, SpeechScriptStatus.AUTO, incremental, script.revision))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Archiving must never break playback: a failed write is a lost convenience, not a lost read.
            logger.warn("Could not archive read-aloud script for target '{}': {}", capture.target.type, e.message)
            return@withContext null
        }
        purgeExpiredAuto(capture.owner)
        stored
    }

    /**
     * User-saved copy: a promotion of the live row that already carries [script]'s id, otherwise a
     * new row. An archived row is never promoted in place — it must stay archived — and its id
     * cannot be reused, because `id` is the primary key.
     */
    suspend fun savePinned(
        capture: SpeechCapture,
        text: String,
        script: SpeechScript,
        title: String? = null,
        promote: Boolean = false
    ): SpeechScriptRecord? {
        val store = store ?: return null
        val existing = if (promote) store.get(capture.owner, script.scriptId) else null
        if (existing != null && existing.status != SpeechScriptStatus.ARCHIVED) {
            val promoted = existing.copy(
                title = title?.take(MAX_TITLE_CHARS) ?: existing.title,
                status = SpeechScriptStatus.PINNED
            )
            return if (store.update(promoted, existing.revision)) promoted else null
        }
        val record = recordOf(
            capture = capture,
            text = text,
            script = script,
            status = SpeechScriptStatus.PINNED,
            incremental = false,
            revision = 1,
            id = if (existing == null && promote) script.scriptId else UUID.randomUUID().toString(),
            title = title
        )
        return store.savePinned(record)
    }

    /**
     * Persist an edit made in the script panel.
     *
     * A missing row is not an error: a script that only ever lived in memory (or one whose archive
     * failed) simply has nothing to update, and the render still went through.
     */
    suspend fun saveEdit(
        owner: String,
        scriptId: String,
        script: SpeechScript,
        expectedRevision: Int
    ): Boolean {
        val store = store ?: return false
        return try {
            val existing = store.get(owner, scriptId) ?: return false
            if (existing.revision != expectedRevision) return false
            val updated = existing.copy(
                script = script,
                revision = script.revision,
                status = if (existing.status == SpeechScriptStatus.AUTO) SpeechScriptStatus.AUTO else existing.status
            )
            store.update(updated, expectedRevision)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Persisting the edit is bookkeeping: the caller already holds the validated revision in
            // memory and is about to speak it, so a failed write must not truncate the stream.
            logger.warn("Could not save the edit to speech script '{}': {}", scriptId, e.message)
            false
        }
    }

    /** Rename or (un)favourite without touching the script body. */
    suspend fun patch(
        owner: String,
        id: String,
        title: String? = null,
        favorite: Boolean? = null,
        expectedRevision: Int? = null
    ): SpeechScriptRecord? {
        val store = store ?: return null
        val existing = store.get(owner, id) ?: return null
        if (expectedRevision != null && existing.revision != expectedRevision) return null
        val updated = existing.copy(
            title = title?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_TITLE_CHARS) ?: existing.title,
            isFavorite = favorite ?: existing.isFavorite
        )
        // Title/favourite are metadata: the script revision stays as it is, and the CAS is skipped
        // on purpose so two tabs renaming the same script cannot fight each other.
        return if (store.update(updated, existing.revision)) updated else null
    }

    /**
     * Archive this owner's AUTO scripts that nobody has spoken for [AUTO_RETENTION_MS].
     *
     * There is no scheduler here, and a background thread for a table that only grows when somebody
     * presses play would be a poor trade. The cost is paid by a write that already touches the
     * table instead: at most one extra `UPDATE` per [PURGE_INTERVAL_MS] per process, and a
     * deployment that never reads aloud never pays at all. A failure is logged and forgotten —
     * expiry is housekeeping, and losing it must not fail a plan that was just saved.
     */
    private suspend fun purgeExpiredAuto(owner: String) {
        val store = store ?: return
        val now = System.currentTimeMillis()
        val last = lastPurgeAt.get()
        if (now - last < PURGE_INTERVAL_MS) return
        if (!lastPurgeAt.compareAndSet(last, now)) return
        try {
            val archived = store.purgeAutoBefore(owner, now - AUTO_RETENTION_MS)
            if (archived > 0) logger.info("Archived {} expired read-aloud scripts", archived)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Could not expire old read-aloud scripts: {}", e.message)
        }
    }

    // ─── Snapshot construction ───────────────────────────────────────────────────
    /**
     * Build the immutable record for [script].
     *
     * The provider block deliberately holds no credentials: `baseUrlHost` is enough to notice a
     * provider change, and an API key in a user-readable row would be a leak with no upside.
     */
    suspend fun recordOf(
        capture: SpeechCapture,
        text: String,
        script: SpeechScript,
        status: SpeechScriptStatus,
        incremental: Boolean,
        revision: Int,
        id: String = script.scriptId,
        title: String? = null
    ): SpeechScriptRecord {
        val now = System.currentTimeMillis()
        return SpeechScriptRecord(
            id = id,
            owner = capture.owner,
            owners = capture.owners,
            targetType = capture.target.type,
            targetId = capture.target.id,
            sessionId = capture.target.sessionId,
            title = title?.take(MAX_TITLE_CHARS) ?: titleOf(text),
            sourceText = text,
            sourceDigest = SpokenTextNormalizer.sourceDigest(text),
            mode = capture.mode,
            status = status,
            revision = revision,
            script = script,
            ttsParams = ttsParamsOf(capture, text, script),
            directionParams = directionOf(capture, incremental),
            createdAt = now,
            updatedAt = now
        )
    }

    private fun ttsParamsOf(capture: SpeechCapture, text: String, script: SpeechScript): SpeechTtsParams {
        val config = capture.modelConfig
        val options = parseOptions(capture.settings.options)
        return SpeechTtsParams(
            capturedAt = System.currentTimeMillis(),
            mode = capture.mode,
            provider = SpeechProviderParams(
                modelConfigId = config.id,
                modelId = config.modelId,
                modelDisplayName = config.name.takeIf { it.isNotBlank() },
                protocol = config.protocol.name.lowercase(),
                baseUrlHost = hostOf(config.baseUrl ?: capture.settings.baseUrl)
            ),
            synthesis = SpeechSynthesisParams(
                format = capture.format,
                mimeType = capture.mimeType,
                sampleRate = capture.sampleRate,
                bitRate = null,
                inputMode = "PLAIN_TEXT",
                timeoutSeconds = capture.settings.timeoutSeconds
            ),
            rowDefaults = SpeechRowDefaults(
                voice = options.string("voice"),
                wordTimestampEnabled = options.boolean("word_timestamp_enabled")
                    ?: options.boolean("enable_word_timestamp")
                    ?: options.boolean("word_timestamp"),
                options = options
            ),
            overrides = capture.overrides,
            perSegment = script.segments.map { segment ->
                SpeechSegmentParams(
                    segmentId = segment.id,
                    voice = segment.voice,
                    emotion = segment.emotion,
                    rate = segment.rate,
                    role = segment.role.name,
                    pauseAfterMs = segment.pauseAfterMs,
                    sourceChars = segment.sourceText.length,
                    spokenChars = segment.spokenText.length
                )
            },
            cost = SpeechCostParams(
                maxChars = capture.maxChars,
                characters = text.length
            )
        )
    }

    /**
     * The planning model, when a plan was directed rather than read straight through.
     *
     * Resolution is repeated here (the director already resolved it) because the archive must
     * record the model *as configured now*; it is a cached lookup, not a network call.
     */
    private suspend fun directionOf(capture: SpeechCapture, incremental: Boolean): SpeechDirectionParams? {
        if (capture.mode != "director") return null
        val resolved = auxModelResolver?.resolve(capture.owners, AuxModelTask.SPEECH_DIRECTION) ?: return null
        val config = resolved.modelConfig
        return SpeechDirectionParams(
            modelConfigId = config.id,
            modelId = config.modelId,
            protocol = config.protocol.name.lowercase(),
            samplingIntent = "DETERMINISTIC",
            thinking = false,
            strategy = if (incremental) "incremental" else "complete",
            promptVersion = SpeechDirector.PROMPT_VERSION,
            promptHash = SpeechDirector.promptFingerprint(),
            schemaVersion = 1,
            timeoutMs = SpeechDirector.planningTimeoutMs(incremental)
        )
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────────

    /**
     * A list label derived from the text itself: first sentence, markdown punctuation flattened.
     *
     * Not an LLM call — a title has to be free, instant, and stable across restarts, and the list
     * page already shows the mode/segment/summary line beside it.
     */
    fun titleOf(text: String): String {
        val flat = text
            .replace(Regex("[`*_>#\\[\\]()]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (flat.isEmpty()) return DEFAULT_TITLE
        val head = flat.take(MAX_TITLE_CHARS)
        val cut = head.indexOfFirst { it in SENTENCE_ENDS }
        val candidate = if (cut >= MIN_TITLE_CHARS) head.substring(0, cut) else head
        return candidate.trim().ifEmpty { DEFAULT_TITLE }
    }

    private fun parseOptions(raw: String?): Map<String, Any?> {
        if (raw.isNullOrBlank()) return emptyMap()
        return runCatching {
            SharedObjectMapper.instance.readValue(raw, object : TypeReference<Map<String, Any?>>() {})
        }
            .onFailure { logger.debug("Unreadable media options on the speech row: {}", it.message) }
            .getOrDefault(emptyMap())
    }

    private fun Map<String, Any?>.string(key: String): String? = this[key]?.toString()?.takeIf { it.isNotBlank() }

    private fun Map<String, Any?>.boolean(key: String): Boolean? = when (val value = this[key]) {
        is Boolean -> value
        is String -> value.toBooleanStrictOrNull()
        is Number -> value.toInt() != 0
        else -> null
    }

    private fun hostOf(baseUrl: String?): String? {
        if (baseUrl.isNullOrBlank()) return null
        return runCatching { URI(baseUrl.trim()).host }.getOrNull()
    }

    companion object {
        private const val MAX_TITLE_CHARS = 60

        private const val MIN_TITLE_CHARS = 12

        private const val DEFAULT_TITLE = "Read-aloud script"

        /** AUTO scripts older than this are archived; a PINNED one is never touched. */
        private const val AUTO_RETENTION_MS = 90L * 24 * 60 * 60 * 1000

        /** At most one expiry sweep per hour, however many scripts get saved in between. */
        private const val PURGE_INTERVAL_MS = 60L * 60 * 1000

        private val SENTENCE_ENDS = charArrayOf('。', '！', '？', '.', '!', '?', '\n', '；', ';')
    }
}
