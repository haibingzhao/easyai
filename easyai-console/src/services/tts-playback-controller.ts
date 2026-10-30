import { i18n } from '@/utils/i18n';
import { TtsError, ttsService, type TtsSpeechResponse } from '@/services/tts-service';

/** idle → loading → playing, with `ready` holding a clip the browser refused to autoplay. */
export type TtsPlaybackState = 'idle' | 'loading' | 'ready' | 'playing';

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
}

export interface TtsPlaybackDeps {
  speak(text: string, signal: AbortSignal): Promise<TtsSpeechResponse>;
  createAudio(): TtsAudioElement;
  createObjectUrl(source: Blob | MediaSource): string;
  revokeObjectUrl(url: string): void;
  /** Optional in tests and older browsers: absence selects the Blob fallback. */
  createMediaSource?(): MediaSource;
  supportsStreaming?(mimeType: string): boolean;
}

const browserDeps: TtsPlaybackDeps = {
  speak: (text, signal) => ttsService.speak(text, signal),
  createAudio: () => new Audio(),
  createObjectUrl: (source) => URL.createObjectURL(source),
  revokeObjectUrl: (url) => URL.revokeObjectURL(url),
  createMediaSource: () => new MediaSource(),
  supportsStreaming: (mimeType) =>
    typeof MediaSource !== 'undefined' && MediaSource.isTypeSupported(mimeType),
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
 */
export class TtsPlaybackController {
  private snapshot: TtsPlaybackSnapshot = IDLE;
  private readonly listeners = new Set<() => void>();
  private audio: TtsAudioElement | null = null;
  private objectUrl: string | null = null;
  private abort: AbortController | null = null;
  private cancelStream: (() => void) | null = null;
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
  async speak(key: string, text: string): Promise<void> {
    const epoch = ++this.epoch;
    this.cancelActiveRequest();
    this.releaseAudio();

    const controller = new AbortController();
    this.abort = controller;
    this.emit({ state: 'loading', activeKey: key, error: null });

    try {
      const speech = await this.deps.speak(text, controller.signal);
      if (epoch !== this.epoch) return;
      const canStream = speech.body !== null &&
        this.deps.createMediaSource !== undefined &&
        this.deps.supportsStreaming?.(speech.mimeType) === true;
      if (canStream) {
        // Keep the AbortController alive until the body is fully consumed.
        await this.startStream(speech, key, epoch, controller.signal);
      } else {
        const blob = await speech.blob();
        if (epoch !== this.epoch) return;
        this.abort = null;
        await this.start(blob, key, epoch);
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

  /** Play a clip that was loaded but blocked by the autoplay policy. */
  async resume(): Promise<void> {
    const { state, activeKey } = this.snapshot;
    if (state !== 'ready' || !activeKey || !this.audio) return;
    const epoch = this.epoch;
    try {
      await this.audio.play();
      if (epoch !== this.epoch) return;
      this.emit({ state: 'playing', activeKey, error: null });
    } catch (e) {
      if (epoch !== this.epoch) return;
      if (isAutoplayBlocked(e)) {
        // Still blocked: keep the clip and the same message rather than dropping it.
        this.emit({ state: 'ready', activeKey, error: null });
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

  /** Append MP3 chunks to MediaSource and start playback after the first successful append. */
  private async startStream(
    speech: TtsSpeechResponse,
    key: string,
    epoch: number,
    signal: AbortSignal
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
      this.emit({ state: 'playing', activeKey: key, error: null });
    } catch (error) {
      if (epoch !== this.epoch) return;
      if (isAutoplayBlocked(error)) {
        this.emit({ state: 'ready', activeKey: key, error: null });
        return;
      }
      this.epoch++;
      this.cancelActiveRequest();
      this.releaseAudio();
      this.emit({ state: 'idle', activeKey: key, error: i18n('Playback failed') });
    }
  }

  private async start(blob: Blob, key: string, epoch: number): Promise<void> {
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
      this.emit({ state: 'playing', activeKey: key, error: null });
    } catch (e) {
      if (epoch !== this.epoch) return;
      if (isAutoplayBlocked(e)) {
        // Not an error: the clip is ready and one more gesture will start it.
        this.emit({ state: 'ready', activeKey: key, error: null });
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
  }

  private releaseAudio(): void {
    const audio = this.audio;
    if (audio) {
      audio.onended = null;
      audio.onerror = null;
      audio.pause();
      audio.src = '';
    }
    this.audio = null;
    if (this.objectUrl) {
      this.deps.revokeObjectUrl(this.objectUrl);
      this.objectUrl = null;
    }
  }

  private emit(next: TtsPlaybackSnapshot): void {
    this.snapshot = next;
    this.listeners.forEach((listener) => listener());
  }
}

function abortError(): DOMException {
  return new DOMException('The read-aloud request was aborted', 'AbortError');
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
