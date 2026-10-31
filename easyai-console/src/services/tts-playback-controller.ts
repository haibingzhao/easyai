import { i18n } from '@/utils/i18n';
import {
  TtsError,
  ttsService,
  type RenderSpeechRequest,
  type SpeechRenderEvent,
  type SpeechTargetRef,
  type SpeechWord,
  type TtsSpeechResponse,
} from '@/services/tts-service';
import { endTtsAudioSession, primeTtsAudio, ttsAudioElement } from '@/services/tts-audio';
import type { ReadAloudMode, ReadAloudTone } from '@/types/settings';

/** idle → loading → playing, with `ready` holding a clip the browser refused to autoplay. */
export type TtsPlaybackState = 'idle' | 'loading' | 'ready' | 'playing';

/**
 * Where a directed read-aloud has got to.
 *
 * A single clip has no progress to report; a plan does, because "which segment is this" is the only
 * thing that makes a 40-second answer's silence interpretable. `played` is what actually became
 * audible, which is why a plan that fails at segment 4 reports 3 rather than pretending the whole
 * thing never happened.
 */
export interface TtsSegmentProgress {
  /** 1-based, for display: "Segment 2/5". */
  index: number;
  /** From the plan; `0` while an incremental plan has not yet declared its length. */
  total: number;
  /** Segments that finished playing before this one. */
  played: number;
  /** Exactly what the voice is saying for this segment. */
  spokenText: string;
  /** Word timings, empty when the provider reported none. */
  words: SpeechWord[];
  /** Index into [words] that is being spoken right now, or -1 between words. */
  wordIndex: number;
}

export interface TtsPlaybackSnapshot {
  state: TtsPlaybackState;
  /**
   * Which message owns the current state; every other bar renders idle. A failed request keeps the
   * key with `state: 'idle'`, so the error is shown under the message that asked for it rather than
   * on every bar in the conversation.
   */
  activeKey: string | null;
  /** Last failure, in display form; cleared by the next request or stop. */
  error: string | null;
  /**
   * Something the listener should know that is not a failure — for example, a director or refine
   * request falling back to the original text. Never used for errors (see [error]).
   */
  notice?: string;
  /** Present only for a directed read-aloud: one clip has no progress worth showing. */
  progress?: TtsSegmentProgress;
}

/** The slice of `HTMLAudioElement` the controller uses, so tests need no DOM. */
export interface TtsAudioElement {
  src: string;
  // Typed exactly like the DOM's own handlers so a real `HTMLAudioElement` still satisfies this
  // interface; the controller only ever assigns zero-argument closures.
  onended: ((this: GlobalEventHandlers, ev: Event) => unknown) | null;
  onerror: ((this: GlobalEventHandlers, ev: Event | string) => unknown) | null;
  play(): Promise<void>;
  pause(): void;
  /** Real elements only: clearing a source is `removeAttribute` + `load()`, not `src = ''`. */
  removeAttribute?(name: string): void;
  load?(): void;
  /** Optional: only read when the plan carried word timings worth highlighting. */
  currentTime?: number;
  ontimeupdate?: ((this: GlobalEventHandlers, ev: Event) => unknown) | null;
}

export interface TtsPlaybackDeps {
  speak(
    text: string,
    signal: AbortSignal,
    mode: ReadAloudMode,
    target?: SpeechTargetRef,
    tone?: ReadAloudTone
  ): Promise<TtsSpeechResponse>;
  /**
   * Directed read-aloud: consume the NDJSON event stream. Optional so a build without the endpoint
   * — or a test that only exercises one clip — still satisfies the interface.
   */
  render?(
    request: RenderSpeechRequest,
    signal: AbortSignal,
    onEvent: (event: SpeechRenderEvent) => void | Promise<void>
  ): Promise<void>;
  createAudio(): TtsAudioElement;
  createObjectUrl(source: Blob | MediaSource): string;
  revokeObjectUrl(url: string): void;
  /** Optional in tests and older browsers: absence selects the Blob fallback. */
  createMediaSource?(): MediaSource;
  supportsStreaming?(mimeType: string): boolean;
  /**
   * Called synchronously from the click that starts a clip, before the request is even sent.
   *
   * iOS only lets an element start with sound inside a gesture, and the clip cannot exist yet at
   * that moment, so the element has to be unlocked up front — see `tts-audio.ts`.
   */
  primeAudio?(): void;
  /** Optional: how to wait out a plan's inter-segment pause. Defaults to a timer. */
  sleep?(ms: number): Promise<void>;
}

const browserDeps: TtsPlaybackDeps = {
  speak: (text, signal, mode, target, tone) => ttsService.speak(text, signal, mode, target, tone),
  render: (request, signal, onEvent) => ttsService.render(request, signal, onEvent),
  // One element for every clip: an iOS unlock is per element and dies with it.
  createAudio: () => ttsAudioElement(),
  createObjectUrl: (source) => URL.createObjectURL(source),
  revokeObjectUrl: (url) => URL.revokeObjectURL(url),
  createMediaSource: () => new MediaSource(),
  supportsStreaming: (mimeType) =>
    typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mimeType),
  primeAudio: () => primeTtsAudio(),
};

const IDLE: TtsPlaybackSnapshot = { state: 'idle', activeKey: null, error: null };

const isAbort = (e: unknown): boolean =>
  e instanceof DOMException ? e.name === 'AbortError' : (e as { name?: string } | null)?.name === 'AbortError';

/** The only `play()` rejection that a later click can still overcome. */
const isAutoplayBlocked = (e: unknown): boolean =>
  (e as { name?: string } | null)?.name === 'NotAllowedError';

/**
 * One shared read-aloud player for the whole conversation.
 *
 * State lives here rather than in each `MessageMetaBar` because only one clip may play at a time: if
 * every bar owned its own `state`, stopping A by starting B would leave A showing a spinner forever.
 *
 * Race handling is the point of this class:
 *  - every request and stop advances a monotonic `epoch`, and every `await` re-checks it, so a late
 *    response from a superseded request is dropped instead of starting playback over a newer one;
 *  - `stop()` aborts the in-flight request, so a `loading` state is cancellable rather than
 *    decorative — the server-side synthesis is cancelled with it;
 *  - the object URL is revoked on stop, on replacement and on natural end, so a Blob or MediaSource
 *    does not pin memory for the life of the tab;
 *  - an autoplay rejection leaves the clip loaded in `ready`: the next click plays it without
 *    paying for a second synthesis.
 *
 * Mobile Safari needs one thing this class cannot provide on its own: the gesture-time unlock of the
 * element it plays through. `createAudio` therefore returns a page-wide element that `primeAudio`
 * has just unlocked, and `releaseAudio` returns that element to "no source" instead of dropping it.
 */
export class TtsPlaybackController {
  private snapshot: TtsPlaybackSnapshot = IDLE;
  private readonly listeners = new Set<() => void>();
  private audio: TtsAudioElement | null = null;
  private objectUrl: string | null = null;
  private abort: AbortController | null = null;
  private cancelStream: (() => void) | null = null;
  /** Resolves the clip the directed player is awaiting; called when the clip is dropped. */
  private settleClip: ((outcome: ClipOutcome) => void) | null = null;
  /** Resolves the "waiting for a tap" park in directed playback, after `resume()` plays. */
  private wakeBlocked: (() => void) | null = null;
  /** Closes the plan's clip queue; set while a directed plan is running. */
  private stopProducer: (() => void) | null = null;
  /** Wakes the inter-segment pause, so `stop()` during a silence is immediate. */
  private wakeSleep: (() => void) | null = null;
  private epoch = 0;

  constructor(private readonly deps: TtsPlaybackDeps = browserDeps) {}

  readonly subscribe = (listener: () => void): (() => void) => {
    this.listeners.add(listener);
    return () => {
      this.listeners.delete(listener);
    };
  };

  readonly getSnapshot = (): TtsPlaybackSnapshot => this.snapshot;

  /** Start speaking `text` for `key`, replacing whatever was playing or loading. */
  async speak(
    key: string,
    text: string,
    mode: ReadAloudMode = 'direct',
    target?: SpeechTargetRef,
    tone: ReadAloudTone = 'default'
  ): Promise<void> {
    // The directed path is a different transport (an event stream, not one audio body), so it owns
    // its own loop; everything that is shared — the epoch, the abort slot, the element — is.
    if (mode === 'director') {
      await this.speakScript(key, { text, target, tone });
      return;
    }
    const epoch = ++this.epoch;
    this.cancelActiveRequest();
    this.releaseAudio();

    // Last synchronous step of the click. Everything below is async, and by the time the clip
    // arrives Safari no longer considers `play()` user-initiated — hence the unlock here rather
    // than at playback time.
    this.deps.primeAudio?.();

    const controller = new AbortController();
    this.abort = controller;
    this.emit({ state: 'loading', activeKey: key, error: null });

    try {
      const speech = await this.deps.speak(text, controller.signal, mode, target, tone);
      if (epoch !== this.epoch) return;
      const notice = refineFallbackNotice(speech.fallbackReason);
      if (notice) this.emit({ state: 'loading', activeKey: key, error: null, notice });
      const canStream = speech.body !== null &&
        this.deps.createMediaSource !== undefined &&
        this.deps.supportsStreaming?.(speech.mimeType) === true;
      if (canStream) {
        // Keep the AbortController alive until the body is fully consumed.
        await this.startStream(speech, key, epoch, controller.signal, notice);
      } else {
        const blob = await speech.blob();
        if (epoch !== this.epoch) return;
        this.abort = null;
        await this.start(blob, key, epoch, notice);
      }
    } catch (e) {
      // A superseded request reports nothing: its state no longer exists.
      if (epoch !== this.epoch) return;
      // A response-body or MediaSource failure must not leave the paid upstream request running.
      this.cancelActiveRequest();
      this.releaseAudio();
      // A deliberate stop surfaces as an abort, which is not an error the user should see.
      this.emit(isAbort(e) ? IDLE : { state: 'idle', activeKey: key, error: messageOf(e) });
    }
  }

  /**
   * Render a plan — a fresh one from `text`, or a stored one by `scriptId` — and play it segment by
   * segment.
   *
   * Segment audio is never concatenated: the clips arrive as separate MP3 bodies, and joining them
   * would have to solve encoder delay and bit-reservoir continuity first. Playing them back to back
   * with their own `pauseAfterMs` gives the same result without inventing a container.
   */
  async speakScript(key: string, request: RenderSpeechRequest): Promise<void> {
    const epoch = ++this.epoch;
    this.cancelActiveRequest();
    this.releaseAudio();

    if (!this.deps.render) {
      // Say so rather than falling back to a direct read: the user asked for a directed voice and
      // silently hearing a different one is worse than hearing nothing.
      this.emit({ state: 'idle', activeKey: key, error: i18n('Directed read-aloud needs a newer server') });
      return;
    }

    // Last synchronous step of the click: safe to prime only once the request will be attempted, so
    // the "needs a newer server" branch above does not leave the audio session claimed.
    this.deps.primeAudio?.();

    const controller = new AbortController();
    this.abort = controller;
    this.emit({ state: 'loading', activeKey: key, error: null });
    try {
      await this.playDirected(key, request, epoch, controller.signal);
    } catch (e) {
      // Without this the bar stays on "loading" forever: nothing above `playDirected` awaits it.
      if (epoch !== this.epoch) return;
      this.cancelActiveRequest();
      this.releaseAudio();
      this.emit(isAbort(e) ? IDLE : { state: 'idle', activeKey: key, error: messageOf(e) });
    }
  }

  /** Play a clip that was loaded but blocked by the autoplay policy. */
  async resume(): Promise<void> {
    const { state, activeKey, progress, notice } = this.snapshot;
    if (state !== 'ready' || !activeKey || !this.audio) return;
    const epoch = this.epoch;
    try {
      await this.audio.play();
      if (epoch !== this.epoch) return;
      this.emit({ state: 'playing', activeKey, error: null, progress, notice });
      // A parked directed plan resumes at the clip it stopped on and keeps going: the wait inside
      // `playClip` ends here, and the rest of the plan is still queued behind it.
      const wake = this.wakeBlocked;
      this.wakeBlocked = null;
      wake?.();
    } catch (e) {
      if (epoch !== this.epoch) return;
      if (isAutoplayBlocked(e)) {
        // Still blocked: keep the clip and the same message rather than dropping it.
        this.emit({ state: 'ready', activeKey, error: null, progress, notice });
        return;
      }
      this.epoch++;
      this.cancelActiveRequest();
      this.releaseAudio();
      this.emit({ state: 'idle', activeKey, error: i18n('Playback failed') });
    }
  }

  /** Stop playback, cancel a pending request and drop the loaded clip. */
  stop(): void {
    this.epoch++;
    this.cancelActiveRequest();
    this.releaseAudio();
    if (this.snapshot.state !== 'idle' || this.snapshot.activeKey !== null || this.snapshot.error !== null) {
      this.emit(IDLE);
    }
  }

  /**
   * Consume the render stream and play its clips in plan order, pausing between them.
   *
   * The stream is drained by a producer running alongside the player, so segment 1 is audible while
   * segment 5 is still being synthesized — but the plan can never run ahead of playback by more than
   * what the server already sent, because nothing is buffered here beyond the queue.
   *
   * Failure is reported, never hidden: a segment that fails after its source retry stops the plan and
   * names how many segments were already heard, which is the only truthful account of a stream whose
   * audio has already left the server.
   */
  private async playDirected(
    key: string,
    request: RenderSpeechRequest,
    epoch: number,
    signal: AbortSignal
  ): Promise<void> {
    const clips = new ClipQueue();
    let total = 0;
    let played = 0;
    /** Next segment index the plan must supply; a gap means audio was lost on the way. */
    let expectedIndex = 0;
    /** Only `done`/`failed` prove the stream was complete — a closed connection is not an ending. */
    let terminated = false;
    let notice: string | undefined;
    let failure: { message: string } | null = null;
    let lastProgress: TtsSegmentProgress | null = null;
    // A stop has to release the producer too: it may be parked inside `push` waiting for room, and a
    // closed queue is what tells it to drop the clip and let the aborted fetch end the stream.
    this.stopProducer = () => clips.close();

    const pump = this.deps.render!(request, signal, async (event) => {
      if (epoch !== this.epoch) return;
      switch (event.type) {
        case 'meta':
          if (event.segmentCount >= 0) total = event.segmentCount;
          // The server says when it had to fall back to a plain read. Passing that on is the
          // difference between "a quieter voice" and a degradation nobody was told about.
          if (!event.planned) notice = i18n('Read plainly: no direction was available');
          break;
        case 'audio':
          await clips.push({
            index: event.index,
            segmentId: event.segmentId,
            spokenText: event.spokenText,
            mimeType: event.mimeType || 'audio/mpeg',
            // The server clamps to 5000 ms; clamping again here keeps a malformed event from parking
            // the plan behind a minute of silence.
            pauseAfterMs: Math.min(MAX_PAUSE_MS, Math.max(0, event.pauseAfterMs || 0)),
            words: event.words ?? [],
            bytes: decodeBase64(event.data),
          });
          break;
        case 'done':
          terminated = true;
          total = Math.max(total, event.segments);
          clips.close();
          break;
        case 'failed':
          terminated = true;
          failure = { message: failureText(event) };
          clips.close();
          break;
        default:
          // Warnings are already repaired into the plan; nothing to do at playback time.
          break;
      }
    });
    // Both handlers are attached immediately: a reject after the loop has finished must not become
    // an unhandled rejection, and it still has to release the waiting producer.
    void pump.then(
      () => clips.close(),
      (error) => {
        if (epoch === this.epoch && !isAbort(error)) {
          failure = failure ?? { message: messageOf(error) };
        }
        clips.close();
      }
    );

    while (epoch === this.epoch) {
      if (clips.isEmpty() && !clips.isClosed()) {
        // The plan is still being written or synthesized: say "loading", not "playing into silence".
        this.emit({ state: 'loading', activeKey: key, error: null, progress: this.snapshot.progress });
      }
      const clip = await clips.next();
      if (epoch !== this.epoch) return;
      if (!clip) break;

      // The plan's own order, and a gap means one segment's audio never arrived. Skipping it would
      // silently drop a sentence, which is the one thing a read-aloud must not do.
      if (clip.index !== expectedIndex) {
        failure = failure ?? { message: i18n('The read-aloud plan skipped a segment') };
        break;
      }
      expectedIndex = clip.index + 1;

      const progress: TtsSegmentProgress = {
        index: clip.index + 1,
        total,
        played,
        spokenText: clip.spokenText,
        words: clip.words,
        wordIndex: -1,
      };
      lastProgress = progress;
      this.emit({ state: 'playing', activeKey: key, error: null, progress });

      const outcome = await this.playClip(clip, key, epoch, progress);
      if (epoch !== this.epoch) return;
      if (outcome === 'released') return;
      if (outcome === 'error') {
        failure = failure ?? { message: i18n('Playback failed') };
        break;
      }
      played++;
      // The pause belongs between clips, not inside one: it is the plan's own beat, and appending
      // silence would mean owning MP3 frame timing this client has no business owning.
      if (clip.pauseAfterMs > 0) {
        await this.pause(clip.pauseAfterMs);
        if (epoch !== this.epoch) return;
      }
    }

    // The loop only ends when the plan is over, so the producer hook must go before the cleanup
    // below closes the queue a second time — and before `stop()` can reach it.
    // Closing first also releases a producer parked on a full queue after a mid-plan failure;
    // `cancelActiveRequest()` alone would only end the read it is not currently blocked on.
    clips.close();
    this.stopProducer = null;
    if (epoch !== this.epoch) return;
    // A stream that never sent `done`/`failed` was cut, not finished. Reporting success here would
    // present a prefix of the plan as the whole reply — the one outcome a listener cannot detect.
    if (!failure && !terminated) {
      failure = { message: i18n('The read-aloud stream ended early') };
    }
    this.cancelActiveRequest();
    this.releaseAudio();

    if (!failure) {
      // Keep the key: a plain read the user was not told about is still worth a line under the reply.
      this.emit(notice ? { state: 'idle', activeKey: key, error: null, notice } : IDLE);
      return;
    }
    // The local count, not the server's: `playedSegments` says how many clips it sent, which on a
    // slow connection is more than the listener actually heard.
    this.emit({
      state: 'idle',
      activeKey: key,
      error: played > 0 ? stoppedMessage(played, total) : failure.message,
      // A plain-read notice still applies when the plan then failed part way: the listener was told
      // about the degradation for a reason, and losing it makes the error read as a total surprise.
      notice,
      progress: lastProgress ? { ...lastProgress, played } : undefined,
    });
  }

  /**
   * Wait out a plan's own beat between segments, but let `stop()` end the wait immediately.
   *
   * A plain `setTimeout` here means the button stays unresponsive for up to five seconds after it is
   * pressed — long enough for the user to conclude the click was missed and press it again.
   */
  private async pause(ms: number): Promise<void> {
    const sleep = this.deps.sleep ?? delay;
    await Promise.race([
      sleep(ms),
      new Promise<void>((resolve) => {
        this.wakeSleep = resolve;
      }),
    ]);
    this.wakeSleep = null;
  }

  /** Play one clip to its end, pausing on an autoplay park instead of dropping the rest of the plan. */
  private async playClip(
    clip: DirectedClip,
    key: string,
    epoch: number,
    progress: TtsSegmentProgress
  ): Promise<ClipOutcome> {
    const audio = this.deps.createAudio();
    this.audio = audio;
    this.objectUrl = this.deps.createObjectUrl(clipBlob(clip.bytes, clip.mimeType));
    audio.src = this.objectUrl;

    let settled = false;
    const finished = new Promise<ClipOutcome>((resolve) => {
      const settle = (outcome: ClipOutcome) => {
        if (settled) return;
        settled = true;
        resolve(outcome);
      };
      this.settleClip = settle;
      audio.onended = () => settle('ended');
      audio.onerror = () => settle('error');
    });

    try {
      await audio.play();
      // A late resolve must not touch the shared player: a newer plan may already own `this.audio`
      // and `this.settleClip`, and continuing would release *its* clip and strand its `finished`.
      if (epoch !== this.epoch) return 'released';
    } catch (error) {
      if (epoch !== this.epoch) return 'released';
      if (!isAutoplayBlocked(error)) {
        this.settleClip = null;
        this.releaseAudio();
        return 'error';
      }
      // Autoplay denied: park here, keep the clip loaded and let `resume()` start it. Dropping the
      // plan would re-bill the synthesis that already ran, and restarting it would replay segment 1.
      this.emit({ state: 'ready', activeKey: key, error: null, progress });
      await new Promise<void>((resolve) => {
        this.wakeBlocked = resolve;
      });
      if (epoch !== this.epoch) return 'released';
    }

    this.followWords(audio, clip, key, epoch, progress);
    const outcome = await finished;
    // `releasePending()` has already resolved this clip as 'released'; clearing the shared slot or
    // releasing the element here would steal the newer plan's clip.
    if (epoch !== this.epoch) return 'released';
    this.settleClip = null;
    // The next segment replaces this clip on the same element, so the session has to survive it.
    this.releaseAudio(true);
    return outcome;
  }

  /**
   * Report the word being spoken, so the plan can be highlighted against the audio.
   *
   * Only a change of word is published: `timeupdate` fires about four times a second, and every
   * emission re-renders every meta bar in the conversation. Absence of timings simply means no
   * highlighting — never an error.
   */
  private followWords(
    audio: TtsAudioElement,
    clip: DirectedClip,
    key: string,
    epoch: number,
    progress: TtsSegmentProgress
  ): void {
    if (clip.words.length === 0 || audio.ontimeupdate === undefined) return;
    let last = -1;
    audio.ontimeupdate = () => {
      if (epoch !== this.epoch) return;
      const index = wordIndexAt(clip.words, Math.round((audio.currentTime ?? 0) * 1000));
      if (index === last) return;
      last = index;
      this.emit({
        state: 'playing',
        activeKey: key,
        error: null,
        progress: { ...progress, wordIndex: index },
      });
    };
  }

  /** Append MP3 chunks to MediaSource and start playback after the first successful append. */
  private async startStream(
    speech: TtsSpeechResponse,
    key: string,
    epoch: number,
    signal: AbortSignal,
    notice?: string
  ): Promise<void> {
    const mediaSource = this.deps.createMediaSource!();
    const audio = this.deps.createAudio();
    this.audio = audio;
    this.objectUrl = this.deps.createObjectUrl(mediaSource);
    audio.src = this.objectUrl;
    audio.onended = () => {
      if (epoch === this.epoch) this.stop();
    };
    audio.onerror = () => {
      if (epoch !== this.epoch) return;
      this.epoch++;
      this.cancelActiveRequest();
      this.releaseAudio();
      this.emit({ state: 'idle', activeKey: key, error: i18n('Playback failed') });
    };

    const reader = speech.body!.getReader();
    const cancelStream = () => {
      // Aborting fetch is not a substitute for cancelling a locked body reader in every browser.
      // Explicitly cancelling the reader closes the HTTP response and propagates disconnect upstream.
      void reader.cancel().catch(() => undefined);
    };
    this.cancelStream = cancelStream;
    let resolveFirst!: () => void;
    let rejectFirst!: (error: unknown) => void;
    const firstChunk = new Promise<void>((resolve, reject) => {
      resolveFirst = resolve;
      rejectFirst = reject;
    });
    const done = (async () => {
      try {
        await waitForSourceOpen(mediaSource, signal);
        const sourceBuffer = mediaSource.addSourceBuffer(speech.mimeType);
        let first = true;
        while (true) {
          const next = await reader.read();
          if (next.done) break;
          if (!next.value?.byteLength) continue;
          await appendChunk(sourceBuffer, next.value, signal);
          if (first) {
            first = false;
            resolveFirst();
          }
        }
        if (first) throw new Error('Speech provider returned an empty stream');
        if (mediaSource.readyState === 'open') mediaSource.endOfStream();
      } catch (error) {
        rejectFirst(error);
        throw error;
      } finally {
        if (this.cancelStream === cancelStream) this.cancelStream = null;
        reader.releaseLock();
      }
    })();
    // Attach both handlers immediately: a stream that fails after playback starts must not become
    // an unhandled rejection, and a late failure from a replaced message must not touch new state.
    void done.then(
      () => {
        if (epoch === this.epoch) this.abort = null;
      },
      (error) => {
        if (epoch !== this.epoch || isAbort(error)) return;
        const message = this.snapshot.state === 'loading' ? messageOf(error) : i18n('Playback failed');
        this.epoch++;
        this.cancelActiveRequest();
        this.releaseAudio();
        this.emit({ state: 'idle', activeKey: key, error: message });
      }
    );

    await firstChunk;
    if (epoch !== this.epoch) return;
    try {
      await audio.play();
      if (epoch !== this.epoch) return;
      this.emit({ state: 'playing', activeKey: key, error: null, notice });
    } catch (error) {
      if (epoch !== this.epoch) return;
      if (isAutoplayBlocked(error)) {
        this.emit({ state: 'ready', activeKey: key, error: null, notice });
        return;
      }
      this.epoch++;
      this.cancelActiveRequest();
      this.releaseAudio();
      this.emit({ state: 'idle', activeKey: key, error: i18n('Playback failed') });
    }
  }

  private async start(blob: Blob, key: string, epoch: number, notice?: string): Promise<void> {
    const audio = this.deps.createAudio();
    this.audio = audio;
    this.objectUrl = this.deps.createObjectUrl(blob);
    audio.src = this.objectUrl;
    audio.onended = () => {
      if (epoch !== this.epoch) return;
      this.stop();
    };
    audio.onerror = () => {
      if (epoch !== this.epoch) return;
      this.epoch++;
      this.releaseAudio();
      this.emit({ state: 'idle', activeKey: key, error: i18n('Playback failed') });
    };

    try {
      await audio.play();
      if (epoch !== this.epoch) return;
      this.emit({ state: 'playing', activeKey: key, error: null, notice });
    } catch (e) {
      if (epoch !== this.epoch) return;
      if (isAutoplayBlocked(e)) {
        // Not an error: the clip is ready and one more gesture will start it.
        this.emit({ state: 'ready', activeKey: key, error: null, notice });
        return;
      }
      // A decode/source failure cannot be retried by clicking again: release the clip and say so,
      // instead of parking it in `ready` where every click silently does nothing.
      this.epoch++;
      this.releaseAudio();
      this.emit({ state: 'idle', activeKey: key, error: i18n('Playback failed') });
    }
  }

  /** Cancel both the fetch and its locked body reader; either one alone can leave bytes flowing. */
  private cancelActiveRequest(): void {
    this.abort?.abort();
    this.abort = null;
    const cancelStream = this.cancelStream;
    this.cancelStream = null;
    cancelStream?.();
    // A plan's producer may be parked waiting for buffer room rather than reading; closing the queue
    // is what releases it, and the abort above is what ends the read it goes back to.
    const stopProducer = this.stopProducer;
    this.stopProducer = null;
    stopProducer?.();
    // The producer/consumer pair is native code, not a fetch: closing the stream stops new segments
    // but does not by itself release a loop waiting on a clip or on a tap.
    this.releasePending();
  }

  /**
   * Unblock everything the directed player may be waiting on.
   *
   * Without this, `stop()` during a clip would leave `playClip` awaiting an `ended` event that a
   * paused element never fires, and the plan would sit in memory until the tab closed.
   */
  private releasePending(): void {
    const settle = this.settleClip;
    this.settleClip = null;
    settle?.('released');
    const wake = this.wakeBlocked;
    this.wakeBlocked = null;
    wake?.();
    // The inter-segment pause is the third thing a stopped plan can be waiting on.
    const wakeSleep = this.wakeSleep;
    this.wakeSleep = null;
    wakeSleep?.();
  }

  /**
   * Drop the loaded clip and its object URL.
   *
   * [keepSession] is for the directed player only: ending the audio session between segments of the
   * same plan makes iOS re-route audio once per sentence — the reply comes out ducked, or briefly on
   * the speaker instead of the headphones. The plan's own teardown ends the session once.
   */
  private releaseAudio(keepSession = false): void {
    const audio = this.audio;
    if (audio) {
      audio.onended = null;
      audio.onerror = null;
      if (audio.ontimeupdate !== undefined) audio.ontimeupdate = null;
      audio.pause();
      // The element outlives the clip (see `createAudio`), so it has to go back to *no source*:
      // assigning `src = ''` points it at the page URL, which Safari reports as a failed load.
      // `load()` also detaches a MediaSource from a reused element.
      if (audio.removeAttribute && audio.load) {
        audio.removeAttribute('src');
        audio.load();
      } else {
        audio.src = '';
      }
    }
    // Outside the `if (audio)`: `primeAudio` claims the audio session at click time, so a request
    // that fails — or a plan whose last clip was already released with `keepSession` — would leave
    // the page holding a `playback` session and keep the phone's other audio suppressed.
    if (!keepSession) endTtsAudioSession();
    this.audio = null;
    if (this.objectUrl) {
      this.deps.revokeObjectUrl(this.objectUrl);
      this.objectUrl = null;
    }
    this.releasePending();
  }

  private emit(next: TtsPlaybackSnapshot): void {
    this.snapshot = next;
    this.listeners.forEach((listener) => listener());
  }
}

/**
 * Turn the server's reason code into the sentence shown under the message.
 *
 * The code is `reason` or `reason:seconds` (`refine-timeout:4`). A timeout is the one case where the
 * budget *is* the diagnosis — "given 4 s for this reply" — so it is quoted; the others name the
 * thing to fix. An unknown code still gets the generic notice: a newer server must never make the
 * fallback silent, because the listener then hears a plain read believing it was refined.
 */
function refineFallbackNotice(reason: TtsSpeechResponse['fallbackReason']): string | undefined {
  if (!reason) return undefined;
  const [code, seconds] = reason.split(':');
  switch (code) {
    case 'length-limit':
      return i18n('Refine skipped: text exceeds the 1,200-character limit; reading the original');
    case 'refine-timeout':
      return seconds
        ? i18n('Refine timed out after {seconds}s; reading the original').replace('{seconds}', seconds)
        : i18n('Refine timed out; reading the original');
    case 'refine-not-configured':
      return i18n('Refine skipped: no Speech Direction Model is configured; reading the original');
    case 'refine-no-voice':
      return i18n('Refine skipped: the TTS model exposes no usable voice; reading the original');
    case 'refine-rejected':
      return i18n('Refine rejected by the fidelity checks; reading the original');
    default:
      return i18n('Refine unavailable; reading the original');
  }
}

function abortError(): DOMException {
  return new DOMException('The read-aloud request was aborted', 'AbortError');
}

/** How one clip's playback ended; `released` means something else took the player away. */
type ClipOutcome = 'ended' | 'error' | 'released';

/** One synthesized segment, ready to play. */
interface DirectedClip {
  index: number;
  segmentId: string;
  spokenText: string;
  mimeType: string;
  pauseAfterMs: number;
  words: SpeechWord[];
  bytes: Uint8Array;
}

/**
 * Hands clips from the stream reader to the player, with a hard cap on how far ahead it may run.
 *
 * The cap is the memory bound of the whole feature: the reader awaits `push`, not reading a chunk is
 * what eventually back-pressures the server's render, and the server in turn stops synthesizing
 * segments nobody is waiting for. Without it a 40-segment plan would be buffered in full before the
 * first word was audible — which is exactly the buffering the plan exists to avoid.
 */
class ClipQueue {
  private items: DirectedClip[] = [];
  private waiters: Array<() => void> = [];
  private closed = false;

  constructor(private readonly capacity: number = MAX_BUFFERED_CLIPS) {}

  async push(clip: DirectedClip): Promise<void> {
    while (!this.closed && this.items.length >= this.capacity) {
      await new Promise<void>((resolve) => this.waiters.push(resolve));
    }
    if (this.closed) return;
    this.items.push(clip);
    this.wake();
  }

  /** No more clips will arrive: an empty queue now means "finished", not "not yet". */
  close(): void {
    this.closed = true;
    this.wake();
  }

  isEmpty(): boolean {
    return this.items.length === 0;
  }

  isClosed(): boolean {
    return this.closed;
  }

  async next(): Promise<DirectedClip | null> {
    while (this.items.length === 0 && !this.closed) {
      await new Promise<void>((resolve) => this.waiters.push(resolve));
    }
    const clip = this.items.shift() ?? null;
    // Taking one clip makes room, so the producer may read the next one.
    if (clip) this.wake();
    return clip;
  }

  private wake(): void {
    const waiters = this.waiters;
    this.waiters = [];
    waiters.forEach((resolve) => resolve());
  }
}

/** Base64 → bytes; the event stream cannot carry raw binary through newline framing. */
function decodeBase64(data: string): Uint8Array {
  const binary = atob(data);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

/**
 * Bytes → Blob, copying into a plain `ArrayBuffer`.
 *
 * `BlobPart` will not take a `Uint8Array` over a possibly-shared buffer, and the copy also frees the
 * decoded array immediately instead of pinning the whole segment until the clip is released.
 */
function clipBlob(bytes: Uint8Array, type: string): Blob {
  const buffer = new ArrayBuffer(bytes.byteLength);
  new Uint8Array(buffer).set(bytes);
  return new Blob([buffer], { type });
}

/** The word being spoken at `ms`, or -1 before the first one and after the last. */
function wordIndexAt(words: SpeechWord[], ms: number): number {
  for (let i = 0; i < words.length; i++) {
    if (ms < words[i].endMs) return ms >= words[i].beginMs ? i : -1;
  }
  return -1;
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

/**
 * How many rendered segments may wait in the queue ahead of playback.
 *
 * Matches the server's own `MAX_BUFFERED_SEGMENTS`: a larger client window would only move the
 * buffering, not remove it, and a smaller one would stall on the plan's own inter-segment pauses.
 *
 * The peak resident set is this queue **plus two**: the clip being played and the one the stream
 * handler has already decoded and is trying to push. The bound is enforced by `await`ing the push,
 * not by a counter — a full queue parks the consumer, which stops reading, which is what eventually
 * back-pressures the server's render.
 */
const MAX_BUFFERED_CLIPS = 3;

/** Matches the server-side clamp: a longer beat between sentences reads as a stall. */
export const MAX_PAUSE_MS = 5_000;

/**
 * How a plan that stopped part way is described.
 *
 * `total` is 0 for a plan whose length the server never declared — the incremental pipeline sends
 * its first segment before it knows how many there are — so the count has to stand on its own
 * rather than read "1 of 0". The singular is a separate key because "after 1 segments" is the kind
 * of sentence that makes an error message look like a bug.
 */
function stoppedMessage(played: number, total: number): string {
  const fill = (key: string): string =>
    i18n(key).replace('{played}', String(played)).replace('{total}', String(total));
  if (total > 0) return fill('Stopped after {played} of {total} segments');
  return fill(played === 1 ? 'Stopped after {played} segment' : 'Stopped after {played} segments');
}

/** The server's failure code decides the wording; the message it sends is provider detail. */
function failureText(event: { code: string }): string {
  switch (event.code) {
    case 'TIMEOUT':
      return i18n('Speech synthesis timed out');
    case 'PLAN_UNAVAILABLE':
      return i18n('The read-aloud script is no longer available');
    case 'EDIT_REJECTED':
      return i18n('The edited script was rejected; adjust it and try again');
    case 'REVISION_CONFLICT':
      return i18n('The script was updated elsewhere; reopen it and try again');
    default:
      return i18n('Speech synthesis failed');
  }
}

function waitForSourceOpen(mediaSource: MediaSource, signal: AbortSignal): Promise<void> {
  if (mediaSource.readyState === 'open') return Promise.resolve();
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      mediaSource.removeEventListener('sourceopen', opened);
      signal.removeEventListener('abort', aborted);
    };
    const opened = () => {
      cleanup();
      resolve();
    };
    const aborted = () => {
      cleanup();
      reject(abortError());
    };
    mediaSource.addEventListener('sourceopen', opened);
    signal.addEventListener('abort', aborted, { once: true });
    if (signal.aborted) aborted();
  });
}

/** SourceBuffer accepts one append at a time; every read is serialized through updateend. */
async function appendChunk(
  sourceBuffer: SourceBuffer,
  chunk: Uint8Array,
  signal: AbortSignal
): Promise<void> {
  while (sourceBuffer.updating) {
    await waitForUpdate(sourceBuffer, signal);
  }
  const updated = waitForUpdate(sourceBuffer, signal);
  const bytes = chunk.buffer.slice(chunk.byteOffset, chunk.byteOffset + chunk.byteLength) as ArrayBuffer;
  sourceBuffer.appendBuffer(bytes);
  await updated;
}

function waitForUpdate(sourceBuffer: SourceBuffer, signal: AbortSignal): Promise<void> {
  return new Promise((resolve, reject) => {
    const cleanup = () => {
      sourceBuffer.removeEventListener('updateend', updated);
      signal.removeEventListener('abort', aborted);
    };
    const updated = () => {
      cleanup();
      resolve();
    };
    const aborted = () => {
      cleanup();
      reject(abortError());
    };
    sourceBuffer.addEventListener('updateend', updated);
    signal.addEventListener('abort', aborted, { once: true });
    if (signal.aborted) aborted();
  });
}

function messageOf(e: unknown): string {
  // The status carries the meaning; the backend body is written for a log, not for a hover title.
  if (e instanceof TtsError) {
    switch (e.status) {
      case 400:
        return i18n('Nothing to read aloud here');
      case 401:
        return i18n('Sign in again to use read-aloud');
      case 404:
        return i18n('The read-aloud script expired; read the reply again to rebuild it');
      case 409:
        return i18n('No text-to-speech model is configured');
      case 413:
        return i18n('This reply is too long to read aloud (max 20,000 characters)');
      case 429:
        return i18n('The previous read-aloud is still running');
      case 504:
        return i18n('Speech synthesis timed out');
      default:
        break;
    }
  }
  return i18n('Speech synthesis failed');
}

/** The one player the conversation shares. */
export const ttsPlayback = new TtsPlaybackController();
