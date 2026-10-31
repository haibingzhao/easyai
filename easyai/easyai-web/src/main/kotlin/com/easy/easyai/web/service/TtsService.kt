package com.easy.easyai.web.service

import com.easy.easyai.api.model.ModelProviderConfig
import com.easy.easyai.api.model.ModelType
import com.easy.easyai.core.media.MediaProviderSettings
import com.easy.easyai.core.model.aux.AuxModelResolver
import com.easy.easyai.core.model.aux.AuxModelTask
import com.easy.easyai.speech.ResolvedSpeechCapabilities
import com.easy.easyai.speech.SpeechAudio
import com.easy.easyai.speech.SpeechInputMode
import com.easy.easyai.speech.SpeechMode
import com.easy.easyai.speech.SpeechOverrides
import com.easy.easyai.speech.SpeechOverrideParams
import com.easy.easyai.speech.SpeechRenderEvent
import com.easy.easyai.speech.SpeechScript
import com.easy.easyai.speech.SpeechScriptRecord
import com.easy.easyai.speech.SpeechScriptStatus
import com.easy.easyai.speech.SpeechScriptTarget
import com.easy.easyai.speech.SpeechScriptValidator
import com.easy.easyai.speech.SpeechSegment
import com.easy.easyai.speech.SpeechSegmentScheduler
import com.easy.easyai.speech.SpeechStyle
import com.easy.easyai.speech.SpeechSynthesizer
import com.easy.easyai.speech.SpeechWarning
import com.easy.easyai.speech.SpokenTextNormalizer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.reactor.asFlux
import kotlinx.coroutines.withTimeout
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.SignalType
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeoutException
import kotlin.time.Duration.Companion.seconds

/** Whether chat read-aloud is usable right now, and why not when it is not. */
data class TtsAvailability(
    val configured: Boolean,
    val modelId: String? = null,
    /** One of [TtsService.REASON_*]; null when [configured]. */
    val reason: String? = null
)

/** A plan the caller can preview, edit and then render by id. */
data class PlannedScript(
    val script: SpeechScript,
    val warnings: List<SpeechWarning>,
    val expiresInSeconds: Long,
    /**
     * True when the plan came from the archive rather than a fresh planning call.
     *
     * The client shows a different header for it ("saved 3 minutes ago") and, more importantly, the
     * user learns that editing this one will overwrite a saved script instead of starting over.
     */
    val reused: Boolean = false
)

/**
 * Chat read-aloud, in two shapes that share configuration, limits and the single-flight slot.
 *
 *  - **one clip** ([speak], [speakStream]): direct read and quick refine. One provider task, one
 *    `audio/mpeg` body — the low-latency path.
 *  - **a plan** ([renderStream]): the director. A validated [SpeechScript] rendered segment by
 *    segment, reported as [SpeechRenderEvent]s so the client can play them in order, honour pauses
 *    and tell a partial read from a complete one.
 *
 * Gated on the caller's effective Task Models `tts` choice, resolved across the same self → group
 * owner set the settings page writes to — an unconfigured task, or a row whose credentials are
 * missing, means the feature is off, and the frontend discovers that through [availability] rather
 * than by rendering a button that fails.
 *
 * Deliberate restrictions, all of them product decisions rather than protocol limits:
 *  - **MP3 only.** Raw PCM/OPUS are not reliable `HTMLAudioElement` inputs, and WAV framing is only
 *    verified for the generation tool, so the read-aloud path pins the format instead of echoing the
 *    row's choice back to the browser.
 *  - **Plain text only.** Message content is Markdown-derived text; a row with `enable_ssml` on must
 *    not turn `**bold**` into markup the service tries to parse.
 *  - **20 000 characters.** The protocol allows 200 000, but read-aloud is billed per character and
 *    is intended for chat replies; over this the request is refused, never truncated.
 *  - **One synthesis per owner at a time.** The button is not the guard; the server is. A request
 *    that replaces the caller's own previous one waits for its cancellation to land instead of
 *    being refused by it — see [SpeechSingleFlight].
 */
@Service
class TtsService(
    @param:Autowired(required = false)
    private val auxModelResolver: AuxModelResolver? = null,
    // Optional, not defaulted-only: Kotlin default arguments are invisible to Spring's constructor
    // resolution, so a plain `synthesizer: SpeechSynthesizer = SpeechSynthesizer.create()` asks the
    // container for a bean that does not exist and the context fails to start.
    @param:Autowired(required = false)
    private val synthesizer: SpeechSynthesizer? = null,
    @param:Autowired(required = false)
    private val speechDirector: SpeechPlanner? = null,
    @param:Autowired(required = false)
    private val planStore: SpeechPlanStore? = null,
    /** Null on a host without R2DBC: read-aloud then keeps working on the in-memory plan only. */
    @param:Autowired(required = false)
    private val archive: SpeechScriptArchiveService? = null
) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** The façade actually used: an injected bean when one exists, the DashScope one otherwise. */
    private val engine: SpeechSynthesizer = synthesizer ?: SpeechSynthesizer.create()

    private val scripts: SpeechPlanStore = planStore ?: SpeechPlanStore()

    /** Bounded, ordered rendering of a plan; see its own documentation. */
    private val scheduler = SpeechSegmentScheduler()

    /** Owners with a synthesis in flight; a replacement waits for its predecessor, a second one is a 429. */
    private val slots = SpeechSingleFlight()

    /**
     * Read-aloud state for the caller; resolves the config but never synthesizes (no cost).
     *
     * Credentials are part of the answer: a row that cannot authenticate would answer 409 on the
     * first click, so reporting it here keeps the button from appearing in the first place.
     */
    suspend fun availability(owners: Collection<String>): TtsAvailability {
        val evaluation = evaluate(owners)
        val config = evaluation.config
        return TtsAvailability(
            configured = config != null && config.hasCredentials(),
            modelId = config?.modelId ?: evaluation.offModelId,
            reason = when {
                config == null -> evaluation.reason
                !config.hasCredentials() -> REASON_INCOMPLETE_CONFIG
                else -> null
            }
        )
    }

    /**
     * Synthesize [text] with the configured `tts` row.
     *
     * [owners] is the self → group owner set; its **first element is the caller**, which is what the
     * single-flight slot is keyed on (`currentGroupOwners()` inserts the user id before the group
     * owners). Keying on any other element would let one member's synthesis reject another's.
     */
    suspend fun speak(
        owners: Collection<String>,
        text: String,
        mode: String = SpeechMode.DIRECT.key,
        target: SpeechTarget = SpeechTarget.manual()
    ): SpeechAudio {
        val request = prepare(owners, text, mode, target)
        try {
            val audio = try {
                withTimeout(request.settings.timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS).seconds) {
                    engine.synthesize(
                        settings = request.settings,
                        text = request.speech,
                        overrides = request.overrides,
                        format = CHAT_FORMAT
                    )
                }
            } catch (e: TimeoutCancellationException) {
                logger.warn("Read-aloud timed out for config '{}' ({} chars)", request.config.id, request.speech.length)
                throw ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Speech synthesis timed out")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logger.warn(
                    "Read-aloud failed for config '{}' ({} chars): {}",
                    request.config.id,
                    request.speech.length,
                    e.message
                )
                throw ResponseStatusException(HttpStatus.BAD_GATEWAY, "Speech synthesis failed")
            }

            logger.info(
                "Read-aloud: config={} model={} chars={} bytes={} mime={}",
                request.config.id,
                request.config.modelId,
                request.speech.length,
                audio.bytes.size,
                audio.mimeType
            )
            return audio
        } finally {
            slots.release(request.lease)
        }
    }

    /**
     * Claim a low-latency read-aloud **before** the response is committed.
     *
     * Same contract as [openRender]: the controller calls this inside its outer `mono { }` so that a
     * 400/409/413/429 from the claim is still a status code. Once this returns, the caller owns the
     * lease and must either stream it with [speakStream] or hand it back with [release].
     */
    internal suspend fun openSpeak(
        owners: Collection<String>,
        text: String,
        mode: String = SpeechMode.DIRECT.key,
        target: SpeechTarget = SpeechTarget.manual()
    ): PreparedSpeech = prepare(owners, text, mode, target)

    /**
     * Low-latency read-aloud: headers are returned before synthesis finishes and every provider
     * frame is forwarded. The single-flight slot lives until the HTTP subscriber completes,
     * fails or cancels; cancellation also propagates into the provider's WebSocket.
     */
    internal fun speakStream(prepared: PreparedSpeech): Flux<SpeechAudio> {
        val startedAt = System.nanoTime()
        var bytes = 0L
        var frames = 0L
        return Flux.defer {
            engine.synthesizeStream(
                settings = prepared.settings,
                text = prepared.speech,
                overrides = prepared.overrides,
                format = CHAT_FORMAT
            )
        }
            .timeout(Duration.ofSeconds(prepared.settings.timeoutSeconds.coerceIn(1, MAX_TIMEOUT_SECONDS)))
            .doOnNext { chunk ->
                frames++
                bytes += chunk.bytes.size
            }
            .doOnComplete {
                logger.info(
                    "Read-aloud stream: config={} model={} chars={} bytes={} frames={} in {} ms",
                    prepared.config.id,
                    prepared.config.modelId,
                    prepared.speech.length,
                    bytes,
                    frames,
                    (System.nanoTime() - startedAt) / 1_000_000
                )
            }
            .doOnError { error ->
                logger.warn(
                    "Read-aloud stream failed for config '{}' ({} chars): {}",
                    prepared.config.id,
                    prepared.speech.length,
                    error.message
                )
            }
            .onErrorMap { error ->
                when (error) {
                    is ResponseStatusException -> error
                    is TimeoutException, is TimeoutCancellationException ->
                        ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT, "Speech synthesis timed out")
                    else -> ResponseStatusException(HttpStatus.BAD_GATEWAY, "Speech synthesis failed")
                }
            }
            .doFinally { signal ->
                if (signal == SignalType.CANCEL) {
                    logger.info(
                        "Read-aloud stream cancelled: config={} chars={} bytes={} frames={}",
                        prepared.config.id,
                        prepared.speech.length,
                        bytes,
                        frames
                    )
                }
                slots.release(prepared.lease)
            }
    }

    /** Hand back a claim that will never be streamed (a cancelled response). Idempotent. */
    internal fun release(prepared: PreparedSpeech) {
        slots.release(prepared.lease)
    }

    /** Hand back a render claim that will never be streamed (a cancelled response). Idempotent. */
    internal fun release(handle: RenderSession) {
        slots.release(handle.session.lease)
    }

    /**
     * Plan [text] without synthesizing anything, so the caller can show (and edit) the script first.
     *
     * Uses whole-plan planning on purpose: a preview is a document, so it has to be complete and
     * fully validated before it is shown. Costs one LLM call and nothing else — unless a stored
     * script still matches [text], in which case it costs nothing at all and comes back with
     * [PlannedScript.reused] set.
     *
     * @param target where [text] came from; it decides which stored script may be reused and how the
     * archive row is filed. Defaults to manual text, which is never shared with a message.
     */
    suspend fun planScript(
        owners: Collection<String>,
        text: String,
        target: SpeechTarget = SpeechTarget.manual()
    ): PlannedScript {
        // No single-flight slot: this call only plans, and a plan is not something a second one can
        // double-bill. Holding it here is what made opening the script panel block the speaker
        // button on the very reply the panel was opened for.
        val session = claim(owners, text, holdSlot = false)
        try {
            val capture = captureOf(session, target, SpeechMode.DIRECTOR)
            reusable(session, capture)?.let { stored ->
                scripts.put(session.owner, stored, session.speech)
                logger.info(
                    "Reused stored read-aloud script '{}' ({} chars, {} segment(s))",
                    stored.scriptId,
                    session.speech.length,
                    stored.segments.size
                )
                return PlannedScript(stored, stored.warnings, SpeechPlanStore.TTL_MS / 1000, reused = true)
            }
            val director = speechDirector
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, MESSAGE_NO_DIRECTOR)
            val scriptId = UUID.randomUUID().toString()
            val collected = mutableListOf<SpeechSegment>()
            val summary = try {
                director.produce(
                    owners = session.owners,
                    text = session.speech,
                    ttsConfig = session.config,
                    maxChars = MAX_CHARS,
                    incremental = false,
                    onSegment = { collected += it }
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SpeechPlanException) {
                throw ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, messageForPlan(e))
            }
            val script = SpeechScript(
                scriptId = scriptId,
                sourceDigest = SpokenTextNormalizer.sourceDigest(session.speech),
                mood = summary.mood,
                style = summary.style,
                segments = collected,
                warnings = summary.warnings
            )
            // Best-effort: a failed archive write must not turn a usable plan into a 500. The archive
            // keeps the id of an existing AUTO row for this target, so take the id it returns: the
            // panel renders by it, and `touchPlayed`/`saveEdit` must find that same row.
            val stored = capture?.let { archive?.saveAuto(it, session.speech, script, incremental = false) }
            val usable = if (stored != null) script.copy(scriptId = stored.id) else script
            scripts.put(session.owner, usable, session.speech)
            return PlannedScript(usable, summary.warnings, SpeechPlanStore.TTL_MS / 1000)
        } finally {
            slots.release(session.lease)
        }
    }

    /**
     * Promote a script to a user-saved one ("keep this").
     *
     * The script itself is not re-planned or re-validated: it is the plan the user is looking at,
     * and the archive already holds the source text an edit would be checked against.
     */
    suspend fun pinScript(
        owners: Collection<String>,
        scriptId: String,
        target: SpeechTarget,
        title: String? = null
    ): SpeechScriptRecord? {
        val archive = archive ?: return null
        val owner = owners.firstOrNull() ?: return null
        val entry = scripts.get(owner, scriptId)
        // Loaded even when the plan is hot: the archive row is what a later save promotes in place,
        // and pinning without it inserts a second row beside the AUTO one (and again on every retry).
        val existing = archive.load(owner, scriptId)
        val script = entry?.script ?: existing?.script ?: return null
        val text = entry?.sourceText ?: existing?.sourceText ?: return null
        val session = claim(owners, text, requireText = false, holdSlot = false)
        // A row that already exists keeps its own target: the client's claim about where the text
        // came from is not evidence, and moving a script between targets would break the one-per-
        // target invariant the archive relies on.
        val effectiveTarget = if (existing != null) {
            SpeechTarget(existing.targetType, existing.targetId, existing.sessionId)
        } else {
            target
        }
        val capture = captureOf(session, effectiveTarget, SpeechMode.DIRECTOR) ?: return null
        return archive.savePinned(capture, text, script, title = title, promote = existing != null)
    }

    /**
     * Render a director plan as a stream of events.
     *
     * Two entry points share one pipeline:
     *  - [scriptId] is null → plan [text] now. A plan that cannot be built before any audio was sent
     *    is not an error: the original text is read instead (`Meta.planned = false`).
     *  - [scriptId] points at a stored plan → render it, with [editedSegments] replacing the plan's
     *    segments when supplied. An edited plan is re-validated from scratch against the source the
     *    server stored, and its revision is bumped, so neither the client's voices nor its
     *    instructions are trusted.
     */
    /**
     * Claim a directed read-aloud **before** the response is committed.
     *
     * Separate from [renderStream] on purpose: a 409/413/404 must reach the client as a status code,
     * and once the controller has wrapped the event flux in a `ResponseEntity` the status line is
     * already `200`. Claiming inside the controller's outer `mono { }` keeps the failure where
     * Spring can still turn it into an error response.
     */
    internal suspend fun openRender(
        owners: Collection<String>,
        text: String,
        scriptId: String?,
        target: SpeechTarget = SpeechTarget.manual()
    ): RenderSession {
        val session = claim(owners, text, requireText = scriptId.isNullOrBlank())
        try {
            if (scriptId.isNullOrBlank()) {
                // The speaker button does not open the preview first, so this is its only chance to
                // reuse the script already filed for the reply. Without it every click directs again.
                val capture = captureOf(session, target, SpeechMode.DIRECTOR)
                val reused = reusable(session, capture)
                if (reused != null) {
                    scripts.put(session.owner, reused, session.speech)
                    val stored = scripts.get(session.owner, reused.scriptId)
                    if (stored != null) {
                        logger.info("Reused stored read-aloud script '{}' for playback", reused.scriptId)
                        return RenderSession(session, stored, target)
                    }
                }
                return RenderSession(session, null, target)
            }
            val stored = scripts.get(session.owner, scriptId) ?: restoreFromArchive(session, scriptId)
                ?: throw ResponseStatusException(
                    HttpStatus.NOT_FOUND,
                    "This read-aloud script has expired; read the reply again to rebuild it"
                )
            // Resolved here so a row without a usable voice is a 409 rather than a stream that dies
            // after its first event.
            capabilitiesFor(session.config)
                ?: throw ResponseStatusException(HttpStatus.CONFLICT, MESSAGE_NO_VOICE)
            return RenderSession(session, stored, target)
        } catch (e: Exception) {
            // The single-flight slot was claimed for work that will never run.
            slots.release(session.lease)
            throw e
        }
    }

    /** Render an already claimed request ([openRender]) as an ordered event stream. */
    internal fun renderStream(
        handle: RenderSession,
        editedSegments: List<SpeechSegment>? = null,
        incremental: Boolean = true
    ): Flux<SpeechRenderEvent> =
        channelFlow<SpeechRenderEvent> {
            try {
                val stored = handle.stored
                if (stored == null) {
                    renderFreshPlan(handle.session, handle.target, incremental) { send(it) }
                } else {
                    renderStoredPlan(handle.session, stored, editedSegments) { send(it) }
                }
            } finally {
                slots.release(handle.session.lease)
            }
        }
            // Rendezvous: one event in flight, so a slow client stalls the scheduler instead of
            // letting rendered clips pile up behind a 64-slot channel.
            .buffer(0)
            .asFlux()
            // The slot is claimed before this flux reaches the controller, so it must be released by
            // the flux as well: a subscriber that goes away before its first event would otherwise
            // hold the owner's single-flight slot for the life of the process. `release` is
            // idempotent, so the channel's own `finally` doing it again is harmless.
            .doFinally { slots.release(handle.session.lease) }

    // ─── Director pipeline ────────────────────────────────────────────────────

    private suspend fun renderFreshPlan(
        session: Session,
        target: SpeechTarget,
        incremental: Boolean,
        emit: suspend (SpeechRenderEvent) -> Unit
    ) {
        val director = speechDirector
        val capture = captureOf(session, target, SpeechMode.DIRECTOR)
        // An existing AUTO row keeps its id through `saveAuto`, so announce that id now: the meta
        // event, the plan cache and the archive must agree or `touchPlayed` misses the row.
        val scriptId = capture?.let { archive?.autoIdFor(it) } ?: UUID.randomUUID().toString()
        val delivered = mutableListOf<SpeechSegment>()
        var planned = false
        var played = 0
        var summary: SpeechPlanner.PlanSummary? = null

        try {
            scheduler.stream(
                render = { segment -> renderSegment(session, segment) },
                deliver = { index, segment, rendered ->
                    played++
                    if (rendered.fellBack) {
                        // Reported before the clip, so the client can flag the sentence it hears.
                        emit(
                            SpeechRenderEvent.Warning(
                                SpeechWarning(
                                    SpeechScriptValidator.CODE_SEGMENT_FALLBACK,
                                    segment.id,
                                    "this segment could not be spoken as planned; it read the original sentence instead"
                                )
                            )
                        )
                    }
                    emit(
                        SpeechRenderEvent.SegmentAudio(
                            segmentId = segment.id,
                            index = index,
                            // Not `segment.spokenText`: a fallback segment speaks its source, and the
                            // event must describe the audio the listener actually gets.
                            spokenText = rendered.spokenText,
                            voice = segment.voice,
                            pauseAfterMs = segment.pauseAfterMs,
                            bytes = rendered.audio.bytes,
                            mimeType = rendered.audio.mimeType,
                            words = rendered.audio.words
                        )
                    )
                },
                produce = { send ->
                    if (director == null) throw SpeechPlanException(MESSAGE_NO_DIRECTOR, SpeechPlanner.CODE_NOT_CONFIGURED)
                    if (incremental) {
                        summary = director.produce(
                            owners = session.owners,
                            text = session.speech,
                            ttsConfig = session.config,
                            maxChars = MAX_CHARS,
                            incremental = true,
                            firstSegmentBudgetMs = SpeechPlanner.FIRST_SEGMENT_BUDGET_MS,
                            onSegment = { segment ->
                                delivered += segment
                                if (!planned) {
                                    planned = true
                                    // File the first usable plan before announcing it. The library is
                                    // then populated even if the client goes away during playback;
                                    // the completed/partial plan below replaces this initial row.
                                    persistPlan(session, capture, scriptId, summary, delivered, incremental)
                                    emit(SpeechRenderEvent.Meta(scriptId, 1, UNKNOWN_SEGMENT_COUNT, planned = true))
                                }
                                send(segment)
                            }
                        )
                    } else {
                        // Whole-plan planning: nothing is synthesized until every gate has passed.
                        val collected = mutableListOf<SpeechSegment>()
                        summary = director.produce(
                            owners = session.owners,
                            text = session.speech,
                            ttsConfig = session.config,
                            maxChars = MAX_CHARS,
                            incremental = false,
                            onSegment = { collected += it }
                        )
                        planned = true
                        persistPlan(session, capture, scriptId, summary, collected, incremental)
                        emit(
                            SpeechRenderEvent.Meta(
                                scriptId = scriptId,
                                revision = 1,
                                segmentCount = collected.size,
                                planned = true,
                                warnings = summary?.warnings.orEmpty()
                            )
                        )
                        collected.forEach { segment ->
                            delivered += segment
                            send(segment)
                        }
                    }
                }
            )
            // The plan the listener actually heard, not just the initial plan: a segment that fell
            // back to its source is part of what "play it again" has to reproduce.
            persistPlan(session, capture, scriptId, summary, delivered, incremental)
            archive?.touchPlayed(session.owner, scriptId)
            emit(SpeechRenderEvent.Completed(played, summary?.warnings.orEmpty()))
            if (played > 0) {
                logger.info(
                    "Directed read-aloud: config={} segments={} chars={} planned={}",
                    session.config.id,
                    played,
                    session.speech.length,
                    planned
                )
            }
        } catch (e: CancellationException) {
            // `saveAuto` is non-cancellable, so stopping/replacing a read cannot roll back this
            // final snapshot. `delivered` may be fuller than the first plan announced above.
            persistPlan(session, capture, scriptId, summary, delivered, incremental)
            throw e
        } catch (e: Exception) {
            if (played > 0) {
                // A provider failure after some clips must not erase the script that was heard.
                persistPlan(session, capture, scriptId, summary, delivered, incremental)
            }
            // Not showing anything is worse than showing the text plainly: if no audio has left the
            // server yet, reading the original text is the fallback this feature promises, whether the
            // plan never arrived or arrived and failed to synthesize.
            if (played == 0) {
                logger.info("Director unavailable for config '{}': {}; reading the text instead", session.config.id, e.message)
                readDirectly(session, scriptId, e, emit)
            } else {
                logger.warn("Directed read-aloud failed after {} segment(s): {}", played, e.message)
                emit(
                    SpeechRenderEvent.Failed(
                        code = failureCode(e),
                        // The provider's own message can carry its base URL and model id; the code is
                        // what the client maps to a user-facing explanation.
                        message = MESSAGE_SYNTHESIS_FAILED,
                        playedSegments = played
                    )
                )
            }
        }
    }

    /** Cache and archive one stable snapshot of the plan produced so far. */
    private suspend fun persistPlan(
        session: Session,
        capture: SpeechCapture?,
        scriptId: String,
        summary: SpeechPlanner.PlanSummary?,
        segments: List<SpeechSegment>,
        incremental: Boolean
    ) {
        if (segments.isEmpty()) return
        val rendered = SpeechScript(
            scriptId = scriptId,
            sourceDigest = SpokenTextNormalizer.sourceDigest(session.speech),
            mood = summary?.mood.orEmpty(),
            style = summary?.style ?: SpeechStyle.NARRATOR,
            segments = segments.toList(),
            warnings = summary?.warnings.orEmpty()
        )
        store(session, rendered)
        capture?.let { archive?.saveAuto(it, session.speech, rendered, incremental = incremental) }
    }

    private suspend fun renderStoredPlan(
        session: Session,
        entry: SpeechPlanStore.Entry,
        edited: List<SpeechSegment>?,
        emit: suspend (SpeechRenderEvent) -> Unit
    ) {
        // Resolved again rather than carried on the handle: the model row can be edited between the
        // claim and the first segment. A null here is reported in-band, because `openRender` already
        // made the 409 case a status code and this one can only be a mid-request edit.
        val capabilities = capabilitiesFor(session.config)
        if (capabilities == null) {
            emit(
                SpeechRenderEvent.Failed(
                    code = SpeechRenderEvent.CODE_PLAN_UNAVAILABLE,
                    message = MESSAGE_NO_VOICE,
                    playedSegments = 0
                )
            )
            return
        }
        val script = when {
            edited == null -> entry.script
            // An empty edit is not "no edit": the client sent segments and none of them survived, so
            // re-rendering the stored plan would speak something the user believed they had removed.
            edited.isEmpty() -> {
                emit(
                    SpeechRenderEvent.Failed(
                        code = SpeechRenderEvent.CODE_EDIT_REJECTED,
                        message = "the edited script has no segments",
                        playedSegments = 0
                    )
                )
                return
            }
            else -> entry.script.copy(revision = entry.script.revision + 1, segments = edited)
        }
        val validation = SpeechScriptValidator.validateFull(script, entry.sourceText, capabilities, MAX_CHARS)
        val usable = validation.script
        if (usable == null) {
            emit(
                SpeechRenderEvent.Failed(
                    code = SpeechRenderEvent.CODE_EDIT_REJECTED,
                    message = validation.fault?.message ?: "the script is not renderable",
                    playedSegments = 0
                )
            )
            return
        }
        // Compared-and-set on the revision the edit was based on: two tabs editing the same script must
        // not have the later save silently overwrite the earlier one's segments. A pure replay has no
        // edit to lose, so it neither needs the CAS nor may fail because another tab edited first.
        if (edited != null &&
            !scripts.putIfRevision(session.owner, usable, entry.script.revision, entry.sourceText)
        ) {
            emit(
                SpeechRenderEvent.Failed(
                    code = SpeechRenderEvent.CODE_REVISION_CONFLICT,
                    message = "the script was updated elsewhere; reopen it to edit the current version",
                    playedSegments = 0
                )
            )
            return
        }
        // The comparison succeeded, so `usable` is what will be spoken — persist that, not the edit
        // the client sent, because the validator may have repaired it.
        if (edited != null) {
            archive?.saveEdit(session.owner, usable.scriptId, usable, entry.script.revision)
        }
        archive?.touchPlayed(session.owner, usable.scriptId)

        var played = 0
        emit(
            SpeechRenderEvent.Meta(
                scriptId = usable.scriptId,
                revision = usable.revision,
                segmentCount = usable.segments.size,
                planned = true,
                warnings = usable.warnings
            )
        )
        try {
            scheduler.run(
                count = usable.segments.size,
                render = { index -> renderSegment(session, usable.segments[index]) },
                emit = { index, rendered ->
                    val segment = usable.segments[index]
                    played++
                    if (rendered.fellBack) {
                        emit(
                            SpeechRenderEvent.Warning(
                                SpeechWarning(
                                    SpeechScriptValidator.CODE_SEGMENT_FALLBACK,
                                    segment.id,
                                    "this segment could not be spoken as planned; it read the original sentence instead"
                                )
                            )
                        )
                    }
                    emit(
                        SpeechRenderEvent.SegmentAudio(
                            segmentId = segment.id,
                            index = index,
                            spokenText = rendered.spokenText,
                            voice = segment.voice,
                            pauseAfterMs = segment.pauseAfterMs,
                            bytes = rendered.audio.bytes,
                            mimeType = rendered.audio.mimeType,
                            words = rendered.audio.words
                        )
                    )
                }
            )
            emit(SpeechRenderEvent.Completed(played, usable.warnings))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn(
                "Stored read-aloud script '{}' failed after {} segment(s): {}",
                entry.script.scriptId,
                played,
                e.message
            )
            emit(
                SpeechRenderEvent.Failed(
                    code = failureCode(e),
                    message = MESSAGE_SYNTHESIS_FAILED,
                    playedSegments = played
                )
            )
        }
    }

    /** Last-resort read of the original text, announced as unplanned so the UI does not pretend. */
    private suspend fun readDirectly(
        session: Session,
        scriptId: String,
        cause: Exception,
        emit: suspend (SpeechRenderEvent) -> Unit
    ) {
        val warning = SpeechWarning(
            CODE_DIRECTOR_FALLBACK,
            null,
            // Fixed wording on purpose: `cause.message` can carry the provider URL or model id, and
            // this string is rendered in the user's UI. The detail is already in the log above.
            "the director produced no usable plan; the original text was read instead"
        )
        emit(
            SpeechRenderEvent.Meta(
                scriptId = scriptId,
                revision = 1,
                segmentCount = 1,
                planned = false,
                warnings = listOf(warning)
            )
        )
        // A missing capability row is not fatal here: the voice can also come from the row's own
        // Generation Options, and `null` leaves that decision to the synthesizer.
        val voice = capabilitiesFor(session.config)?.defaultVoice.orEmpty()
        val direct = SpeechSegment(
            id = SINGLE_SEGMENT_ID,
            index = 0,
            sourceText = session.speech,
            spokenText = session.speech,
            voice = voice
        )
        try {
            val rendered = renderSegment(session, direct)
            emit(
                SpeechRenderEvent.SegmentAudio(
                    segmentId = direct.id,
                    index = 0,
                    spokenText = rendered.spokenText,
                    voice = direct.voice,
                    pauseAfterMs = 0,
                    bytes = rendered.audio.bytes,
                    mimeType = rendered.audio.mimeType,
                    words = rendered.audio.words
                )
            )
            emit(SpeechRenderEvent.Completed(1, listOf(warning)))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Plain read-aloud fallback failed too: {}", e.message)
            emit(
                SpeechRenderEvent.Failed(
                    code = failureCode(e),
                    message = MESSAGE_SYNTHESIS_FAILED,
                    playedSegments = 0
                )
            )
        }
    }

    /**
     * One segment, with the documented degradation: if the planned voice/instruction/emotion fails,
     * the segment is spoken again from its **source sentence** with the model's default voice. That
     * is the only retry — a second failure stops the plan instead of silently skipping the sentence.
     */
    private suspend fun renderSegment(session: Session, segment: SpeechSegment): RenderedSegment {
        // Liveness for the single-flight guard: a plan of many segments is one long request, and only
        // its own progress tells that apart from a claim whose holder is gone.
        slots.touch(session.lease)
        val instructions = SpeechOverrides(
            voice = segment.voice,
            instruction = segment.styleNote,
            emotionTag = segment.emotion?.let { "[$it]" },
            rate = segment.rate,
            inputMode = SpeechInputMode.PLAIN_TEXT
        )
        return try {
            RenderedSegment(synthesizeClip(session, segment.spokenText, instructions), segment.spokenText)
        } catch (e: OversizedSegmentException) {
            // Retrying an over-size clip with its source text produces the same over-size clip: this is
            // a provider fault, not a bad rewrite, so it must not cost a second paid call.
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Segment '{}' failed ({}); retrying its source sentence", segment.id, e.message)
            // The retry speaks the source sentence in the row's default voice: the first attempt may
            // have failed *because of* the planned voice or style note, so reusing them would fail
            // again and bill twice.
            val fallbackVoice = capabilitiesFor(session.config)?.defaultVoice
            val retried = synthesizeClip(
                session,
                segment.sourceText,
                SpeechOverrides(voice = fallbackVoice, inputMode = SpeechInputMode.PLAIN_TEXT)
            )
            RenderedSegment(retried, segment.sourceText, fellBack = true)
        }
    }

    /** A clip plus the text it actually speaks — a fallback segment speaks its source, not its plan. */
    private data class RenderedSegment(
        val audio: SpeechAudio,
        val spokenText: String,
        val fellBack: Boolean = false
    )

    private suspend fun synthesizeClip(
        session: Session,
        text: String,
        overrides: SpeechOverrides
    ): SpeechAudio {
        val audio = engine.synthesize(session.settings, text, overrides, CHAT_FORMAT)
        if (audio.bytes.size > SpeechSegmentScheduler.MAX_SEGMENT_BYTES) {
            throw OversizedSegmentException(
                "the provider returned ${audio.bytes.size} bytes for one segment, over the per-segment limit"
            )
        }
        return audio
    }

    /** A single clip bigger than the scheduler's ceiling; retrying cannot fix it. */
    private class OversizedSegmentException(message: String) : RuntimeException(message)

    /** Maps a provider or plan failure onto the code the client turns into an explanation. */
    private fun failureCode(error: Throwable): String = when {
        error is TimeoutCancellationException -> SpeechRenderEvent.CODE_TIMEOUT
        error is TimeoutException -> SpeechRenderEvent.CODE_TIMEOUT
        error is SpeechPlanException && error.code == SpeechPlanner.CODE_TIMEOUT ->
            SpeechRenderEvent.CODE_TIMEOUT
        else -> SpeechRenderEvent.CODE_SEGMENT_FAILED
    }

    private fun store(session: Session, script: SpeechScript) {
        if (script.segments.isEmpty()) return
        scripts.put(session.owner, script, session.speech)
    }

    // ─── Archive bridge ───────────────────────────────────────────────────────

    /**
     * The capture for [target], or null when archiving is off or the target is unusable.
     *
     * A target that names nothing (an unknown type, or a non-manual target without an id) is
     * dropped rather than guessed: filing a script under the wrong target is worse than not filing
     * it, because it then becomes reusable for text it was never written for.
     */
    private fun captureOf(
        session: Session,
        target: SpeechTarget,
        mode: SpeechMode,
        overrides: SpeechOverrideParams = SpeechOverrideParams()
    ): SpeechCapture? {
        if (archive?.available != true) return null
        val type = SpeechScriptTarget.fromKey(target.type) ?: return null
        if (type != SpeechScriptTarget.MANUAL_TEXT && target.id.isNullOrBlank()) return null
        return SpeechCapture(
            owner = session.owner,
            owners = session.owners,
            target = target,
            mode = mode.key,
            modelConfig = session.config,
            settings = session.settings,
            format = CHAT_FORMAT,
            mimeType = CHAT_MIME,
            sampleRate = CHAT_SAMPLE_RATE,
            maxChars = MAX_CHARS,
            overrides = overrides
        )
    }

    /**
     * A stored plan that may still be spoken for this text, re-validated against the *current* model.
     *
     * Two gates, deliberately separate: the archive checks the digest (this script belongs to this
     * text), and the validator checks that the voices and lengths still hold for the model that is
     * configured now. Either failure means "plan it again", never "speak it anyway".
     */
    private suspend fun reusable(session: Session, capture: SpeechCapture?): SpeechScript? {
        val capture = capture ?: return null
        val stored = archive?.reusable(capture, session.speech) ?: return null
        val capabilities = capabilitiesFor(session.config) ?: return null
        val validation = SpeechScriptValidator.validateFull(stored.script, stored.sourceText, capabilities, MAX_CHARS)
        val usable = validation.script
        if (usable == null) {
            logger.info(
                "Stored read-aloud script '{}' is no longer reusable ({}); planning again",
                stored.id,
                validation.fault?.code
            )
            return null
        }
        return usable
    }

    /**
     * Load a script from the archive into the hot cache, so `/render {scriptId}` survives a restart
     * and a second device.
     */
    private suspend fun restoreFromArchive(session: Session, scriptId: String): SpeechPlanStore.Entry? {
        val record = archive?.load(session.owner, scriptId) ?: return null
        if (record.status == SpeechScriptStatus.ARCHIVED) return null
        scripts.put(session.owner, record.script, record.sourceText)
        logger.info("Restored read-aloud script '{}' from the archive", scriptId)
        return scripts.get(session.owner, scriptId)
    }

    private suspend fun capabilitiesFor(config: ModelProviderConfig): ResolvedSpeechCapabilities? =
        speechDirector?.capabilitiesFor(config)

    // ─── Shared request handling ──────────────────────────────────────────────

    /** Validate and claim the caller's slot before any paid provider work starts. */
    private suspend fun prepare(
        owners: Collection<String>,
        text: String,
        mode: String,
        target: SpeechTarget = SpeechTarget.manual()
    ): PreparedSpeech {
        val speechMode = SpeechMode.fromKey(mode)
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown read-aloud mode: $mode")
        if (speechMode == SpeechMode.DIRECTOR) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "Directed read-aloud renders a plan; use POST /api/tts/render"
            )
        }
        val session = claim(owners, text)
        try {
            val capture = if (speechMode == SpeechMode.REFINE) {
                captureOf(session, target, speechMode)
            } else {
                null
            }
            // A one-clip read can only reuse a one-segment script. A multi-segment director plan is
            // still reusable through /render, but cannot be squeezed into this audio response.
            val reusedScript = reusable(session, capture)?.takeIf { it.segments.size == 1 }
            var fallbackReason = if (speechMode == SpeechMode.REFINE) {
                reusedScript?.warnings?.firstOrNull {
                    it.code == FALLBACK_LENGTH_LIMIT || it.code == FALLBACK_UNAVAILABLE
                }?.code ?: if (reusedScript == null && session.speech.length > SpeechDirector.REFINE_MAX_CHARS) {
                    FALLBACK_LENGTH_LIMIT
                } else {
                    null
                }
            } else {
                null
            }
            val refinedScript = if (speechMode == SpeechMode.REFINE) {
                if (reusedScript != null) {
                    logger.info("Reused stored read-aloud script '{}' for refined playback", reusedScript.scriptId)
                    reusedScript
                } else {
                    try {
                        speechDirector?.quickRefine(session.owners, session.speech, session.config, MAX_CHARS)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        logger.warn("Speech planning failed; using direct read-aloud: {}", e.message)
                        null
                    }
                }
            } else {
                null
            }
            // A fake or future planner must not bypass the product limit; an invalid result is only
            // a planning failure, so the original request still gets its direct fallback.
            val usableSegment = refinedScript?.segments?.singleOrNull()
                ?.takeIf { it.spokenText.isNotBlank() && it.spokenText.length <= MAX_CHARS }
            val finalText = usableSegment?.spokenText ?: session.speech
            if (speechMode == SpeechMode.REFINE && usableSegment == null && fallbackReason == null) {
                fallbackReason = FALLBACK_UNAVAILABLE
            }
            val overrides = if (usableSegment == null) {
                SpeechOverrides(inputMode = SpeechInputMode.PLAIN_TEXT)
            } else {
                SpeechOverrides(
                    voice = usableSegment.voice,
                    instruction = usableSegment.styleNote,
                    emotionTag = usableSegment.emotion?.let { "[$it]" },
                    rate = usableSegment.rate,
                    inputMode = SpeechInputMode.PLAIN_TEXT
                )
            }
            if (speechMode == SpeechMode.REFINE && capture != null) {
                if (reusedScript != null && usableSegment != null) {
                    store(session, reusedScript)
                    archive?.touchPlayed(session.owner, reusedScript.scriptId)
                } else {
                    // Archive the mode the user selected and the text they actually heard. A refine
                    // fallback is still a refine-mode read; only an explicitly direct read stays out.
                    val script = if (refinedScript != null && usableSegment != null) {
                        refinedScript.copy(
                            scriptId = UUID.randomUUID().toString(),
                            sourceDigest = SpokenTextNormalizer.sourceDigest(session.speech),
                            segments = listOf(usableSegment)
                        )
                    } else {
                        SpeechScript(
                            scriptId = UUID.randomUUID().toString(),
                            sourceDigest = SpokenTextNormalizer.sourceDigest(session.speech),
                            mood = "",
                            style = SpeechStyle.NARRATOR,
                            segments = listOf(
                                SpeechSegment(
                                    id = SINGLE_SEGMENT_ID,
                                    index = 0,
                                    sourceText = session.speech,
                                    spokenText = finalText,
                                    voice = capabilitiesFor(session.config)?.defaultVoice.orEmpty()
                                )
                            ),
                            warnings = listOfNotNull(
                                fallbackReason?.let {
                                    SpeechWarning(code = it, message = "Refine was unavailable; read the original text")
                                }
                            )
                        )
                    }
                    val stored = archive?.saveAuto(capture, session.speech, script, incremental = false)
                    val saved = if (stored != null) script.copy(scriptId = stored.id) else script
                    store(session, saved)
                    stored?.let { archive?.touchPlayed(session.owner, it.id) }
                }
            }
            return PreparedSpeech(
                session.config,
                session.settings,
                finalText,
                session.lease,
                overrides,
                fallbackReason
            )
        } catch (e: Exception) {
            slots.release(session.lease)
            throw e
        }
    }

    /**
     * Length, configuration and single-flight checks shared by every read-aloud shape.
     *
     * [requireText] is false when the caller renders a script it already has: the source text then
     * lives in the plan store, and demanding a copy of it would let a client "render" a script
     * against text the server never planned.
     *
     * [holdSlot] is false for a preview ([planScript]): a plan spends LLM tokens, not TTS characters,
     * so it has nothing to serialize against a read-aloud — and taking the slot for it made an opened
     * script panel refuse the reply's own speaker button with "a read-aloud is already running" while
     * the panel was still planning and nothing at all was being synthesized.
     */
    private suspend fun claim(
        owners: Collection<String>,
        text: String,
        requireText: Boolean = true,
        holdSlot: Boolean = true
    ): Session {
        val speech = text.trim()
        if (requireText && speech.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "Nothing to read aloud")
        }
        // Same unit on both sides: JS `String.length` and Kotlin `String.length` are UTF-16 units.
        // Only the text is length-limited; a render by `scriptId` carries no text in the first place.
        if (requireText && speech.length > MAX_CHARS) {
            throw ResponseStatusException(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "Text exceeds the $MAX_CHARS-character read-aloud limit"
            )
        }

        val evaluation = evaluate(owners)
        val config = evaluation.config
            ?: throw ResponseStatusException(HttpStatus.CONFLICT, messageFor(evaluation.reason))
        val owner = owners.firstOrNull()?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED, "No owner in scope")
        val settings = config.asSpeechSettings()
        if (settings.baseUrl.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The TTS model has no Base URL (Settings -> Models -> Speech)"
            )
        }
        if (settings.apiKey.isBlank()) {
            throw ResponseStatusException(
                HttpStatus.CONFLICT,
                "The TTS model has no API key (set it on the model's group)"
            )
        }
        // Claimed last, so a request that was never going to run cannot look like a running one.
        // Null means the slot is genuinely busy: either a second concurrent read-aloud, or the
        // request this one replaces has not finished cancelling yet (the guard waits for that).
        if (!holdSlot) return Session(config, settings, speech, owner, owners.toList(), null)
        val lease = slots.acquire(owner) ?: throw ResponseStatusException(
            HttpStatus.TOO_MANY_REQUESTS,
            "A read-aloud request is already running; wait for it to finish"
        )
        return Session(config, settings, speech, owner, owners.toList(), lease)
    }

    /**
     * Resolve the `tts` row and check that it is actually usable.
     *
     * The checks repeat what `PUT /api/aux-models` enforces because a choice can predate that
     * validation, or point at a row that was disabled, retyped or re-pointed afterwards.
     */
    private suspend fun evaluate(owners: Collection<String>): Evaluation {
        val config = auxModelResolver?.resolveConfig(owners, AuxModelTask.TTS)
            ?: return Evaluation(config = null, reason = REASON_NOT_CONFIGURED)
        return when {
            config.modelType != ModelType.SPEECH -> Evaluation(null, REASON_WRONG_MODEL_TYPE, config.modelId)
            !config.enabled -> Evaluation(null, REASON_DISABLED, config.modelId)
            !engine.supports(config.asSpeechSettings()) -> Evaluation(null, REASON_UNSUPPORTED_PROVIDER, config.modelId)
            else -> Evaluation(config, null)
        }
    }

    private fun messageFor(reason: String?): String = when (reason) {
        REASON_WRONG_MODEL_TYPE ->
            "The TTS Model is not a speech row; pick a SPEECH model in Settings -> Task Models -> TTS Model"
        REASON_DISABLED ->
            "The configured TTS model is disabled; enable it in Settings -> Models -> Speech"
        REASON_UNSUPPORTED_PROVIDER ->
            "The configured TTS model uses a provider read-aloud does not support yet; " +
                "use a DashScope (Qwen-Audio-TTS) speech model"
        REASON_INCOMPLETE_CONFIG ->
            "The TTS model is incomplete: it needs a model ID and a Base URL on the row, " +
                "and an API key on its group"
        else -> "No text-to-speech model is configured (Settings -> Task Models -> TTS Model)"
    }

    private fun messageForPlan(e: SpeechPlanException): String =
        when (e.code) {
            SpeechPlanner.CODE_NOT_CONFIGURED -> MESSAGE_NO_DIRECTOR
            else -> "The read-aloud script could not be built: ${e.message}"
        }

    /** A row can be the right type and still be unusable without credentials. */
    private fun ModelProviderConfig.hasCredentials(): Boolean =
        !baseUrl.isNullOrBlank() && !apiKey.isNullOrBlank() && modelId.isNotBlank()

    /** The `tts` task points at a model_provider_config row; the speech module speaks MediaProviderSettings. */
    private fun ModelProviderConfig.asSpeechSettings(): MediaProviderSettings = MediaProviderSettings(
        id = id,
        displayName = name,
        enabled = true,
        serviceKind = MediaProviderSettings.SERVICE_KIND_SPEECH,
        providerType = protocol.name.lowercase(),
        baseUrl = baseUrl.orEmpty(),
        apiKey = apiKey.orEmpty(),
        defaultModel = modelId,
        options = mediaOptions.orEmpty(),
        timeoutSeconds = timeoutSeconds,
        isDefault = true
    )

    /**
     * A validated read-aloud request with its claimed single-flight slot.
     *
     * `internal` rather than `private` so [RenderSession] can carry it to the controller without
     * exposing it: the controller needs the handle, never the fields.
     */
    internal data class Session(
        val config: ModelProviderConfig,
        val settings: MediaProviderSettings,
        val speech: String,
        val owner: String,
        val owners: List<String>,
        /**
         * The single-flight slot this request holds; identity is the proof of ownership. Null for a
         * request that never synthesizes (a plan preview), which therefore has nothing to release.
         */
        val lease: SpeechSingleFlight.Lease?
    )

    /**
     * A claimed directed read-aloud: everything [renderStream] needs, and nothing a caller can act on.
     *
     * It exists so validation can finish before the response is committed — see [openRender].
     */
    internal class RenderSession internal constructor(
        internal val session: Session,
        /** The plan being re-rendered, or null when [renderStream] must plan the text itself. */
        internal val stored: SpeechPlanStore.Entry?,
        /** Where the text came from, so a freshly planned script is filed under the right target. */
        internal val target: SpeechTarget
    )

    /** A validated read-aloud request with its claimed slot; `internal` so [openSpeak] can hand it over. */
    internal data class PreparedSpeech(
        val config: ModelProviderConfig,
        val settings: MediaProviderSettings,
        val speech: String,
        val lease: SpeechSingleFlight.Lease?,
        val overrides: SpeechOverrides,
        /** Why a requested refine became a plain read; null for direct or successful refinement. */
        val fallbackReason: String? = null
    )

    /** A config plus, when it is unusable, why — [offModelId] still lets the UI name the broken row. */
    private data class Evaluation(
        val config: ModelProviderConfig?,
        val reason: String?,
        val offModelId: String? = null
    )

    companion object {
        /** Product limit for one read-aloud request; the protocol's 200 000 stays a lower-level guard. */
        const val MAX_CHARS = 20_000

        /** Read-aloud is always MP3 — see the class comment. */
        private const val CHAT_FORMAT = "mp3"

        /** Pinned with the format: a snapshot that says `mp3` but serves `audio/wav` explains nothing. */
        private const val CHAT_MIME = "audio/mpeg"

        /** The protocol's default when a row omits one; recorded so a sound change is explainable. */
        private const val CHAT_SAMPLE_RATE = 24_000

        /** Same ceiling the DashScope transport applies; the service one must not be looser. */
        private const val MAX_TIMEOUT_SECONDS = 300L

        /** `-1` tells the client the length is not known yet (an incrementally planned script). */
        const val UNKNOWN_SEGMENT_COUNT = -1

        const val REASON_NOT_CONFIGURED = "NOT_CONFIGURED"
        const val REASON_WRONG_MODEL_TYPE = "WRONG_MODEL_TYPE"
        const val REASON_DISABLED = "DISABLED"
        const val REASON_UNSUPPORTED_PROVIDER = "UNSUPPORTED_PROVIDER"
        const val REASON_INCOMPLETE_CONFIG = "INCOMPLETE_CONFIG"

        const val CODE_DIRECTOR_FALLBACK = "DIRECTOR_FALLBACK"

        /** Stable response-header values; the client owns the localized explanation. */
        const val FALLBACK_LENGTH_LIMIT = "length-limit"
        const val FALLBACK_UNAVAILABLE = "unavailable"

        private const val SINGLE_SEGMENT_ID = "seg_0"

        private const val MESSAGE_NO_DIRECTOR =
            "Directed read-aloud needs a Speech Direction Model (Settings -> Task Models)"

        private const val MESSAGE_NO_VOICE =
            "The TTS model exposes no usable voice, so a plan cannot be spoken"

        /** Provider-neutral wording; the specific cause is logged, not shown. */
        private const val MESSAGE_SYNTHESIS_FAILED = "The speech provider failed on a segment"
    }
}
