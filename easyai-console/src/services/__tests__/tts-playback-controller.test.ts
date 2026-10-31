import { describe, expect, it } from 'vitest';
import {
  TtsPlaybackController,
  type TtsAudioElement,
  type TtsPlaybackDeps,
} from '../tts-playback-controller';
import type { RenderSpeechRequest, SpeechRenderEvent, SpeechTargetRef, TtsSpeechResponse } from '../tts-service';
import type { ReadAloudMode } from '@/types/settings';

/** A stand-in for `HTMLAudioElement` that records what the controller asked it to do. */
class FakeAudio implements TtsAudioElement {
  src = '';
  onended: ((this: GlobalEventHandlers, ev: Event) => unknown) | null = null;
  onerror: ((this: GlobalEventHandlers, ev: Event | string) => unknown) | null = null;
  currentTime = 0;
  ontimeupdate: ((this: GlobalEventHandlers, ev: Event) => unknown) | null = null;
  playCalls = 0;
  pauseCalls = 0;
  /** Present only on a "reusable" fake, mirroring a real element the controller keeps across clips. */
  removeAttribute?: (name: string) => void;
  load?: () => void;
  loadCalls = 0;
  private pendingPlay: (() => void) | null = null;

  constructor(
    private blockPlay: boolean,
    holdPlay = false,
    private playFailure: Error | null = null,
    reusable = false
  ) {
    if (holdPlay) this.pendingPlay = () => {};
    if (reusable) {
      this.removeAttribute = (name) => {
        if (name === 'src') this.src = '';
      };
      this.load = () => {
        this.loadCalls++;
      };
    }
  }

  play(): Promise<void> {
    this.playCalls++;
    if (this.playFailure) return Promise.reject(this.playFailure);
    if (this.blockPlay) return Promise.reject(autoplayBlocked());
    if (this.pendingPlay === null) return Promise.resolve();
    // Held open so a test can act in the window between "play() called" and "playback started".
    return new Promise<void>((resolve) => {
      this.pendingPlay = resolve;
    });
  }

  /** Make the *next* `play()` stay pending until [releasePlay]. */
  releasePlay(): void {
    this.pendingPlay?.();
    this.pendingPlay = null;
  }

  /** Let a later gesture succeed, the way a real element does once the user taps again. */
  allowPlay(): void {
    this.blockPlay = false;
  }

  pause(): void {
    this.pauseCalls++;
  }

  /** Fire `ended` the way the browser does after the last sample. */
  end(): void {
    (this.onended as unknown as ((ev: Event) => void) | null)?.(new Event('ended'));
  }
}

class FakeSourceBuffer {
  updating = false;
  readonly chunks: ArrayBuffer[] = [];
  private readonly listeners = new Set<() => void>();

  appendBuffer(chunk: ArrayBuffer): void {
    this.chunks.push(chunk);
    this.updating = true;
    queueMicrotask(() => {
      this.updating = false;
      this.listeners.forEach((listener) => listener());
    });
  }

  addEventListener(_type: string, listener: () => void): void {
    this.listeners.add(listener);
  }

  removeEventListener(_type: string, listener: () => void): void {
    this.listeners.delete(listener);
  }
}

class FakeMediaSource {
  readyState = 'closed';
  readonly sourceBuffer = new FakeSourceBuffer();
  endOfStreamCalls = 0;
  private readonly listeners = new Set<() => void>();

  open(): void {
    this.readyState = 'open';
    this.listeners.forEach((listener) => listener());
  }

  addSourceBuffer(): FakeSourceBuffer {
    return this.sourceBuffer;
  }

  endOfStream(): void {
    this.endOfStreamCalls++;
    this.readyState = 'ended';
  }

  addEventListener(_type: string, listener: () => void): void {
    this.listeners.add(listener);
  }

  removeEventListener(_type: string, listener: () => void): void {
    this.listeners.delete(listener);
  }
}

interface PendingRequest {
  text: string;
  signal: AbortSignal;
  mode: ReadAloudMode;
  target?: SpeechTargetRef;
  resolve(blob: Blob): void;
  resolveResponse(response: TtsSpeechResponse): void;
  reject(error: unknown): void;
}

function setup(
  options: {
    blocked?: boolean;
    holdPlay?: boolean;
    playError?: Error;
    streaming?: boolean;
    reusable?: boolean;
  } = {}
) {
  const requests: PendingRequest[] = [];
  const audios: FakeAudio[] = [];
  const mediaSources: FakeMediaSource[] = [];
  const revoked: string[] = [];
  const primes: string[] = [];
  let urlSeq = 0;

  const deps: TtsPlaybackDeps = {
    speak: (text, signal, mode, target) =>
      new Promise<TtsSpeechResponse>((resolve, reject) => {
        requests.push({
          text,
          signal,
          mode,
          target,
          resolve: (blob) => resolve({
            mimeType: 'audio/mpeg',
            body: null,
            blob: () => Promise.resolve(blob),
          }),
          resolveResponse: resolve,
          reject,
        });
      }),
    createAudio: () => {
      const audio = new FakeAudio(
        options.blocked ?? false,
        options.holdPlay ?? false,
        options.playError ?? null,
        options.reusable ?? false
      );
      audios.push(audio);
      return audio;
    },
    createObjectUrl: (source) => {
      if (source instanceof FakeMediaSource) queueMicrotask(() => source.open());
      return `blob:${++urlSeq}`;
    },
    revokeObjectUrl: (url) => revoked.push(url),
    createMediaSource: options.streaming ? () => {
      const mediaSource = new FakeMediaSource();
      mediaSources.push(mediaSource);
      return mediaSource as unknown as MediaSource;
    } : undefined,
    supportsStreaming: options.streaming ? () => true : undefined,
    primeAudio: () => primes.push('prime'),
  };

  return { controller: new TtsPlaybackController(deps), requests, audios, mediaSources, revoked, primes };
}

const abortError = (): Error => Object.assign(new Error('aborted'), { name: 'AbortError' });

/** What Chrome/Firefox reject `play()` with when autoplay is denied. */
const autoplayBlocked = (): Error =>
  Object.assign(new Error('play() blocked by the autoplay policy'), { name: 'NotAllowedError' });

describe('TtsPlaybackController', () => {
  it('moves loading → playing and tags the clip with its message key', async () => {
    const { controller, requests, audios } = setup();
    const done = controller.speak('m1', 'hello');

    expect(controller.getSnapshot()).toEqual({ state: 'loading', activeKey: 'm1', error: null });
    expect(requests[0].text).toBe('hello');
    expect(requests[0].mode).toBe('direct');

    requests[0].resolve(new Blob(['mp3']));
    await done;

    expect(controller.getSnapshot()).toEqual({ state: 'playing', activeKey: 'm1', error: null });
    expect(audios).toHaveLength(1);
    expect(audios[0].src).toBe('blob:1');
  });

  it('shows a visible notice when a refined read falls back to the original', async () => {
    const { controller, requests } = setup();
    const done = controller.speak('m1', 'hello', 'refine');

    requests[0].resolveResponse({
      mimeType: 'audio/mpeg',
      body: null,
      fallbackReason: 'length-limit',
      blob: () => Promise.resolve(new Blob(['mp3'])),
    });
    await done;

    expect(controller.getSnapshot()).toEqual({
      state: 'playing',
      activeKey: 'm1',
      error: null,
      notice: 'Refine skipped: text exceeds the 1,200-character limit; reading the original',
    });
  });

  it('forwards the message target for a refined read', async () => {
    const { controller, requests } = setup();
    const target: SpeechTargetRef = { type: 'session_message', id: 'msg-1', sessionId: 'sess-1' };
    const done = controller.speak('m1', 'hello', 'refine', target);

    expect(requests[0].mode).toBe('refine');
    expect(requests[0].target).toEqual(target);

    requests[0].resolve(new Blob(['mp3']));
    await done;
  });

  it('unlocks the audio element inside the click, not after the first await', async () => {
    const { controller, requests, primes } = setup();
    const done = controller.speak('m1', 'hello');

    // iOS drops the gesture at the first `await`; an unlock that runs later would leave the phone
    // silent while the desktop, which needs no unlock at all, works.
    expect(primes).toHaveLength(1);
    expect(requests[0].text).toBe('hello');

    requests[0].resolve(new Blob(['mp3']));
    await done;

    expect(primes).toHaveLength(1);
  });

  it('returns a reused element to no source instead of pointing it at the page URL', async () => {
    const { controller, requests, audios } = setup({ reusable: true });
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    await done;

    controller.stop();

    // `src = ''` resolves to the page URL, which Safari reports as a failed load on the element the
    // next clip has to reuse.
    expect(audios[0].loadCalls).toBe(1);
    expect(audios[0].src).toBe('');
  });

  it('aborts the in-flight request and drops its late response when another message speaks', async () => {
    const { controller, requests, audios } = setup();
    void controller.speak('a', 'first');
    const second = controller.speak('b', 'second');

    expect(requests[0].signal.aborted).toBe(true);
    expect(controller.getSnapshot().activeKey).toBe('b');

    // A late answer for `a` must neither play nor overwrite `b`'s state.
    requests[0].resolve(new Blob(['stale']));
    await Promise.resolve();
    expect(controller.getSnapshot()).toEqual({ state: 'loading', activeKey: 'b', error: null });

    requests[1].resolve(new Blob(['fresh']));
    await second;
    expect(controller.getSnapshot().state).toBe('playing');
    // Exactly one clip was ever created, and the single object URL belongs to it: the stale blob
    // never reached `createObjectUrl`, so there is nothing of it to revoke.
    expect(audios).toHaveLength(1);
    expect(audios[0].src).toBe('blob:1');
  });

  it('stops a pending request, cancels it upstream and goes idle', async () => {
    const { controller, requests } = setup();
    void controller.speak('m1', 'hello');
    controller.stop();

    expect(requests[0].signal.aborted).toBe(true);
    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
  });

  it('plays the first streamed chunk before the body ends and aborts the rest on stop', async () => {
    const { controller, requests, audios, mediaSources } = setup({ streaming: true });
    let stream!: ReadableStreamDefaultController<Uint8Array>;
    let cancelCalls = 0;
    const body = new ReadableStream<Uint8Array>({
      start(source) {
        stream = source;
      },
      cancel() {
        cancelCalls++;
      },
    });
    const done = controller.speak('m1', 'hello');
    requests[0].resolveResponse({
      mimeType: 'audio/mpeg',
      body,
      blob: () => Promise.reject(new Error('streaming response must not be buffered')),
    });
    stream.enqueue(Uint8Array.of(1, 2, 3));
    await done;

    expect(controller.getSnapshot()).toEqual({ state: 'playing', activeKey: 'm1', error: null });
    expect(audios[0].playCalls).toBe(1);
    expect(mediaSources[0].sourceBuffer.chunks).toHaveLength(1);
    expect(mediaSources[0].endOfStreamCalls).toBe(0);

    controller.stop();
    await Promise.resolve();
    expect(requests[0].signal.aborted).toBe(true);
    expect(cancelCalls).toBe(1);
    expect(controller.getSnapshot().state).toBe('idle');
  });

  it('aborts a still-streaming body when audio playback fails', async () => {
    const { controller, requests, audios } = setup({ streaming: true });
    let stream!: ReadableStreamDefaultController<Uint8Array>;
    let cancelCalls = 0;
    const body = new ReadableStream<Uint8Array>({
      start(source) {
        stream = source;
      },
      cancel() {
        cancelCalls++;
      },
    });
    const done = controller.speak('m1', 'hello');
    requests[0].resolveResponse({
      mimeType: 'audio/mpeg',
      body,
      blob: () => Promise.reject(new Error('streaming response must not be buffered')),
    });
    stream.enqueue(Uint8Array.of(1, 2, 3));
    await done;

    (audios[0].onerror as unknown as ((ev: Event) => void) | null)?.(new Event('error'));
    await Promise.resolve();

    expect(requests[0].signal.aborted).toBe(true);
    expect(cancelCalls).toBe(1);
    expect(controller.getSnapshot().error).toBe('Playback failed');
  });

  it('does not report a stop as a failure', async () => {
    const { controller, requests } = setup();
    void controller.speak('m1', 'hello');
    requests[0].reject(abortError());
    await Promise.resolve();

    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
  });

  it('keeps the clip for a second click when autoplay is blocked', async () => {
    const { controller, requests, audios } = setup({ blocked: true });
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    await done;

    expect(controller.getSnapshot()).toEqual({ state: 'ready', activeKey: 'm1', error: null });

    audios[0].allowPlay();
    await controller.resume();
    expect(controller.getSnapshot()).toEqual({ state: 'playing', activeKey: 'm1', error: null });
    expect(audios[0].playCalls).toBe(2);
  });

  it('keeps a still-blocked clip ready instead of dropping it', async () => {
    const { controller, requests, audios } = setup({ blocked: true });
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    await done;

    await controller.resume();
    // It really tried to play again and did not surface an error, rather than merely preserving
    // the snapshot it already had.
    expect(audios[0].playCalls).toBe(2);
    expect(controller.getSnapshot()).toEqual({ state: 'ready', activeKey: 'm1', error: null });
  });

  it('reports a failure on the message that asked for it, and clears it on stop', async () => {
    const { controller, requests } = setup();
    const done = controller.speak('m1', 'hello');
    requests[0].reject(new Error('Speech synthesis failed'));
    await done;

    expect(controller.getSnapshot()).toEqual({
      state: 'idle',
      activeKey: 'm1',
      error: 'Speech synthesis failed',
    });

    controller.stop();
    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
  });

  it('stays stopped when a stop lands while play() is still pending', async () => {
    const { controller, requests, audios } = setup({ holdPlay: true });
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    // Let `start()` reach `await audio.play()`, then stop before the browser starts playing.
    await Promise.resolve();
    await Promise.resolve();
    expect(audios).toHaveLength(1);

    controller.stop();
    audios[0].releasePlay();
    await done;

    // `start()` resumes after the stop and must not report "playing" over the idle state.
    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
  });

  it('cannot be resurrected by a replaced clip firing its own handlers late', async () => {
    const { controller, requests, audios } = setup();
    const first = controller.speak('a', 'first');
    requests[0].resolve(new Blob(['1']));
    await first;
    // A handler the browser already queued keeps a reference even after the controller detaches.
    const staleEnd = audios[0].onended;

    const second = controller.speak('b', 'second');
    requests[1].resolve(new Blob(['2']));
    await second;

    (staleEnd as unknown as ((ev: Event) => void) | null)?.call(audios[0], new Event('ended'));
    expect(controller.getSnapshot()).toEqual({ state: 'playing', activeKey: 'b', error: null });
  });

  it('revokes the object URL when playback ends naturally', async () => {
    const { controller, requests, audios, revoked } = setup();
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    await done;

    audios[0].end();

    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
    expect(revoked).toEqual(['blob:1']);
    expect(audios[0].pauseCalls).toBe(1);
    expect(audios[0].src).toBe('');
  });

  it('revokes the previous clip when it is replaced', async () => {
    const { controller, requests, revoked } = setup();
    const first = controller.speak('a', 'first');
    requests[0].resolve(new Blob(['1']));
    await first;

    const second = controller.speak('b', 'second');
    expect(revoked).toEqual(['blob:1']);

    requests[1].resolve(new Blob(['2']));
    await second;

    expect(controller.getSnapshot()).toEqual({ state: 'playing', activeKey: 'b', error: null });
  });

  it('reports a playback error against the key and releases the clip', async () => {
    const { controller, requests, audios, revoked } = setup();
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    await done;

    (audios[0].onerror as unknown as ((ev: Event) => void) | null)?.(new Event('error'));

    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: 'm1', error: 'Playback failed' });
    expect(revoked).toEqual(['blob:1']);
  });

  it('releases a clip whose play() fails for a reason a click cannot fix', async () => {
    // Only NotAllowedError is worth keeping the clip for; a source/decode failure would otherwise
    // park a Play button that silently does nothing on every click.
    const playError = Object.assign(new Error('unsupported source'), { name: 'NotSupportedError' });
    const { controller, requests, audios, revoked } = setup({ playError });
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    await done;

    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: 'm1', error: 'Playback failed' });
    expect(revoked).toEqual(['blob:1']);
    expect(audios[0].pauseCalls).toBe(1);
  });
});

const metaEvent = (segmentCount: number): SpeechRenderEvent => ({
  type: 'meta', scriptId: 's1', revision: 1, segmentCount, planned: true, warnings: [],
});

const doneEvent = (segments: number): SpeechRenderEvent => ({ type: 'done', segments, warnings: [] });

const clipEvent = (
  index: number,
  pauseAfterMs = 0,
  words: Array<{ text: string; beginMs: number; endMs: number }> = []
): SpeechRenderEvent => ({
  type: 'audio',
  segmentId: `seg_${index}`,
  index,
  spokenText: `第${index}句。`,
  voice: 'v',
  pauseAfterMs,
  mimeType: 'audio/mpeg',
  // The transport is text, so the audio is Base64 — the controller has to get real bytes back.
  data: btoa('mp3-' + index),
  words,
});

/** Drives the NDJSON stream by hand so playback order can be observed step by step. */
function setupDirected(options: { blocked?: boolean; hangSleep?: boolean; holdPlay?: boolean } = {}) {
  const audios: FakeAudio[] = [];
  const revoked: string[] = [];
  const pauses: number[] = [];
  const requests: RenderSpeechRequest[] = [];
  let signal: AbortSignal | null = null;
  let emit: ((event: SpeechRenderEvent) => void | Promise<void>) | null = null;
  let finish: (() => void) | null = null;
  let fail: ((error: unknown) => void) | null = null;
  let releaseSleep: (() => void) | null = null;
  let urlSeq = 0;

  const deps: TtsPlaybackDeps = {
    speak: () => Promise.reject(new Error('a directed read must not use /speak')),
    render: (request, requestSignal, onEvent) => {
      requests.push(request);
      signal = requestSignal;
      emit = onEvent;
      return new Promise<void>((resolve, reject) => {
        finish = resolve;
        fail = reject;
      });
    },
    createAudio: () => {
      const audio = new FakeAudio(options.blocked ?? false, options.holdPlay ?? false);
      audios.push(audio);
      return audio;
    },
    createObjectUrl: () => `blob:${++urlSeq}`,
    revokeObjectUrl: (url) => revoked.push(url),
    primeAudio: () => undefined,
    // The inter-segment beat is a real timer in the browser; recording it keeps the test instant
    // while still proving the pause is taken between clips rather than inside one. `hangSleep` makes
    // the wait observable, which is how "stop during a pause" can be tested at all.
    sleep: (ms) => {
      pauses.push(ms);
      if (options.hangSleep) return new Promise<void>((resolve) => { releaseSleep = resolve; });
      return Promise.resolve();
    },
  };

  return {
    controller: new TtsPlaybackController(deps),
    audios,
    revoked,
    pauses,
    requests,
    push: (event: SpeechRenderEvent) => {
      const handled = emit?.(event);
      // The real transport awaits the handler and lets it reject `render()`; a handler that throws
      // here must fail the stream the same way instead of becoming an unhandled rejection.
      if (handled && typeof handled.then === 'function') {
        void handled.catch((error: unknown) => fail?.(error));
      }
    },
    close: () => finish?.(),
    abortWith: (error: unknown) => fail?.(error),
    signal: () => signal,
    releaseSleep: () => releaseSleep?.(),
  };
}

/** Let every pending microtask and the injected zero-delay pause run. */
const flush = async (): Promise<void> => {
  for (let i = 0; i < 8; i++) await Promise.resolve();
};

describe('TtsPlaybackController directed playback', () => {
  it('plays the plan a segment at a time, pausing between clips', async () => {
    const { controller, audios, pauses, requests, push } = setupDirected();
    const done = controller.speak('m1', 'the reply', 'director');

    expect(requests[0]).toEqual({ text: 'the reply' });
    expect(controller.getSnapshot()).toMatchObject({ state: 'loading', activeKey: 'm1' });

    // The server publishes by `index` (SpeechSegmentScheduler), so the client plays what arrives in
    // the order it arrives: a reorder buffer here would have to guess how long to wait for a missing
    // index, and the one component that knows the order is the one that already sorted it.
    push(metaEvent(2));
    push(clipEvent(0, 250));
    push(clipEvent(1, 0));
    push(doneEvent(2));
    await flush();

    expect(audios).toHaveLength(1);
    expect(controller.getSnapshot().progress).toMatchObject({ index: 1, total: 2, played: 0 });

    audios[0].end();
    await flush();
    // The plan's own beat, taken between clips rather than appended into one of them.
    expect(pauses).toEqual([250]);
    expect(audios).toHaveLength(2);
    expect(controller.getSnapshot().progress).toMatchObject({ index: 2, total: 2, played: 1 });

    audios[1].end();
    await done;
    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
  });

  it('does not let a superseded plan tear down the one that replaced it', async () => {
    // `play()` can stay pending indefinitely on iOS. If the old plan resumes after its epoch changed,
    // any shared-field cleanup there (settle slot, audio element, object URL) belongs to the *new*
    // plan and must not run.
    const { controller, audios, push, close } = setupDirected({ holdPlay: true });
    void controller.speakScript('m1', { text: 'old' });
    push(metaEvent(1));
    push(clipEvent(0));
    await flush();
    expect(audios).toHaveLength(1);

    const done = controller.speakScript('m2', { text: 'new' });
    push(metaEvent(1));
    push(clipEvent(0));
    await flush();
    expect(audios).toHaveLength(2);

    // The old element finally starts; its plan is long gone and must release nothing.
    audios[0].releasePlay();
    await flush();

    expect(audios[1].src).not.toBe('');
    expect(audios[1].pauseCalls).toBe(0);
    expect(controller.getSnapshot()).toMatchObject({ state: 'playing', activeKey: 'm2' });

    // The new plan still completes normally, which it cannot if the old plan stole its settle slot.
    audios[1].releasePlay();
    await flush();
    push(doneEvent(1));
    close();
    audios[1].end();
    await done;

    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
  });

  it('reports how much was heard when the plan fails part way through', async () => {
    const { controller, audios, push, close } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    push(metaEvent(3));
    push(clipEvent(0));
    // The real stream sends `failed` and only then ends; the client must not pretend the reply was
    // read, and must not offer a rollback for audio that already played.
    push({ type: 'failed', code: 'SEGMENT_FAILED', message: 'provider said no', playedSegments: 1 });
    close();
    await flush();

    audios[0].end();
    await done;

    expect(controller.getSnapshot().error).toBe('Playback stopped after 1 of 3 segments');
    expect(controller.getSnapshot().progress).toMatchObject({ played: 1 });
  });

  it('names a playback failure without claiming any plan progress', async () => {
    const { controller, audios, push, close } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    push(metaEvent(2));
    push(clipEvent(0));
    push(clipEvent(1));
    close();
    await flush();

    (audios[0].onerror as unknown as ((ev: Event) => void) | null)?.(new Event('error'));
    await done;

    // One failed clip is not "0 of 2 segments": nothing about a plan was read, and saying so is
    // more useful than a count that implies partial success.
    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeKey: 'm1', error: 'Playback failed' });
    expect(audios).toHaveLength(1);
  });

  it('stops a plan mid-segment and cancels the stream that is still producing it', async () => {
    const { controller, audios, signal, push } = setupDirected();
    void controller.speakScript('m1', { text: 'x' });

    push(metaEvent(5));
    push(clipEvent(0));
    await flush();
    expect(audios).toHaveLength(1);

    controller.stop();

    expect(signal()?.aborted).toBe(true);
    expect(controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
    // The clip was released, not left paused with its object URL alive.
    expect(audios[0].pauseCalls).toBe(1);
  });

  it('parks a blocked plan and finishes it after the tap that unblocks it', async () => {
    const directed = setupDirected({ blocked: true });
    const done = directed.controller.speakScript('m1', { text: 'x' });
    directed.push(metaEvent(1));
    directed.push(clipEvent(0, 0));
    directed.push(doneEvent(1));
    directed.close();
    await flush();

    // Autoplay denied: the plan is kept, not re-synthesized, and the bar explains the next tap.
    expect(directed.controller.getSnapshot()).toMatchObject({ state: 'ready', activeKey: 'm1' });

    directed.audios[0].allowPlay();
    await directed.controller.resume();
    await flush();
    expect(directed.controller.getSnapshot()).toMatchObject({ state: 'playing' });

    directed.audios[0].end();
    await done;
    expect(directed.controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
  });

  it('publishes the word being spoken only when it changes', async () => {
    const directed = setupDirected();
    const done = directed.controller.speakScript('m1', { text: 'x' });
    const words = [
      { text: '第一', beginMs: 0, endMs: 400 },
      { text: '句', beginMs: 400, endMs: 900 },
    ];
    directed.push(metaEvent(1));
    directed.push(clipEvent(0, 0, words));
    directed.push(doneEvent(1));
    directed.close();
    await flush();

    const audio = directed.audios[0];
    const timeUpdate = audio.ontimeupdate as unknown as (() => void) | null;

    audio.currentTime = 0.1;
    timeUpdate?.();
    expect(directed.controller.getSnapshot().progress).toMatchObject({ wordIndex: 0 });

    const afterFirst = directed.controller.getSnapshot();
    // Same word again: no emission, or every meta bar in the conversation re-renders four times a
    // second for as long as the reply is being read.
    timeUpdate?.();
    expect(directed.controller.getSnapshot()).toBe(afterFirst);

    audio.currentTime = 0.6;
    timeUpdate?.();
    expect(directed.controller.getSnapshot().progress).toMatchObject({ wordIndex: 1 });

    audio.end();
    await done;
  });

  it('reports a stream that fails before any audio as an error, not as silence', async () => {
    const { controller, abortWith } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    abortWith(new TypeError('network down'));
    await done;

    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeKey: 'm1' });
    expect(controller.getSnapshot().error).toBe('Speech synthesis failed');
  });

  it('names a plan that was cut off before it said anything at all', async () => {
    const { controller, push, close } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    // Meta only: the connection then closes with no `done` and no `failed`. Silence here is
    // indistinguishable from success unless the client says otherwise.
    push(metaEvent(3));
    close();
    await done;

    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeKey: 'm1' });
    expect(controller.getSnapshot().error).toBe('The read-aloud stream ended early');
  });

  it('refuses to skip a segment whose audio never arrived', async () => {
    const { controller, push, close } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    // Segment 0 never came; playing 1 next would silently drop a sentence out of the middle.
    push(metaEvent(2));
    push(clipEvent(1));
    close();
    await done;

    expect(controller.getSnapshot().error).toBe('The read-aloud plan skipped a segment');
  });

  it.each([
    ['TIMEOUT', 'Speech synthesis timed out'],
    ['PLAN_UNAVAILABLE', 'The read-aloud script is no longer available; reopen it to edit the current version'],
    ['EDIT_REJECTED', 'The edited script was rejected; adjust it and try again'],
    ['REVISION_CONFLICT', 'The script was updated elsewhere; reopen it and try again'],
    ['SEGMENT_FAILED', 'Speech synthesis failed'],
  ])('words a %s failure from its code, not from the provider message', async (code, expected) => {
    const { controller, push, close } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    push(metaEvent(2));
    push({ type: 'failed', code, message: 'qwen-tts at dashscope.aliyuncs.com said 500', playedSegments: 0 });
    close();
    await done;

    // The server's message is provider detail; the code is the part written for the listener.
    expect(controller.getSnapshot().error).toBe(expected);
  });

  it('tells the listener when the reply is being read plainly', async () => {
    const { controller, audios, push, close } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    // No plan was produced, so the server read the source text instead. A quieter voice is exactly
    // the kind of degradation a listener blames on their own hearing unless it is named.
    push({ type: 'meta', scriptId: 's1', revision: 1, segmentCount: 1, planned: false, warnings: [] });
    push(clipEvent(0));
    push(doneEvent(1));
    close();
    await flush();

    audios[0].end();
    await done;

    expect(controller.getSnapshot()).toMatchObject({
      state: 'idle',
      activeKey: 'm1',
      error: null,
      notice: 'Read plainly: no direction was available for this reply',
    });
  });

  it('counts the segments it heard when an incremental plan never declared a total', async () => {
    const { controller, audios, push, close } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    // The incremental pipeline sends `-1` as the count: it publishes segment 1 before it knows how
    // many there will be. "1 of 0" would be a lie, so only the count that is known is reported.
    push({ type: 'meta', scriptId: 's1', revision: 1, segmentCount: -1, planned: true, warnings: [] });
    push(clipEvent(0));
    push({ type: 'failed', code: 'SEGMENT_FAILED', message: 'gone', playedSegments: 1 });
    close();
    await flush();

    audios[0].end();
    await done;

    expect(controller.getSnapshot().error).toBe('Playback stopped after 1 segment');
    expect(controller.getSnapshot().progress).toMatchObject({ index: 1, total: 0, played: 1 });
  });

  it('ends a plan immediately when it is stopped during the plan\'s own pause', async () => {
    const directed = setupDirected({ hangSleep: true });
    const done = directed.controller.speakScript('m1', { text: 'x' });
    directed.push(metaEvent(3));
    directed.push(clipEvent(0, 800));
    directed.push(clipEvent(1, 0));
    directed.push(doneEvent(3));
    await flush();

    directed.audios[0].end();
    await flush();
    // The plan is now inside its inter-segment silence, held open by the fake timer.
    expect(directed.pauses).toEqual([800]);

    let settled = false;
    void done.then(() => { settled = true; });
    await flush();
    expect(settled).toBe(false);

    directed.controller.stop();
    await done;

    // Stop has to wake the pause: the bar already reads "idle", and a click that keeps a promise
    // alive for another five seconds is a click the user will make again.
    expect(settled).toBe(true);
    expect(directed.controller.getSnapshot()).toEqual({ state: 'idle', activeKey: null, error: null });
    // And the plan did not resume behind the stop.
    expect(directed.audios).toHaveLength(1);
    expect(directed.audios[0].playCalls).toBe(1);
    expect(directed.pauses).toEqual([800]);
  });

  it('fails the plan instead of hanging on a segment whose audio does not decode', async () => {
    const { controller, audios, push } = setupDirected();
    const done = controller.speakScript('m1', { text: 'x' });

    push(metaEvent(2));
    // A corrupted line cannot become a Blob; the decoder throws out of the event handler, and the
    // only safe outcome is to stop the plan rather than sit in `loading` forever.
    push({ ...clipEvent(0), data: '***not base64***' } as SpeechRenderEvent);
    await done;

    expect(audios).toHaveLength(0);
    expect(controller.getSnapshot()).toMatchObject({ state: 'idle', activeKey: 'm1' });
    expect(controller.getSnapshot().error).toBe('Speech synthesis failed');
  });

  it('refuses to fake a directed read on a server without the event stream', async () => {
    const controller = new TtsPlaybackController({
      speak: () => Promise.reject(new Error('no')),
      createAudio: () => new FakeAudio(false),
      createObjectUrl: () => 'blob:1',
      revokeObjectUrl: () => undefined,
    });

    await controller.speak('m1', 'hello', 'director');

    expect(controller.getSnapshot()).toMatchObject({
      state: 'idle',
      activeKey: 'm1',
      error: 'Directed read-aloud needs a newer server; please upgrade and try again',
    });
  });
});
