import { describe, expect, it } from 'vitest';
import {
  TtsPlaybackController,
  type TtsAudioElement,
  type TtsPlaybackDeps,
} from '../tts-playback-controller';
import type { TtsSpeechResponse } from '../tts-service';

/** A stand-in for `HTMLAudioElement` that records what the controller asked it to do. */
class FakeAudio implements TtsAudioElement {
  src = '';
  onended: ((this: GlobalEventHandlers, ev: Event) => unknown) | null = null;
  onerror: ((this: GlobalEventHandlers, ev: Event | string) => unknown) | null = null;
  playCalls = 0;
  pauseCalls = 0;
  private pendingPlay: (() => void) | null = null;

  constructor(private blockPlay: boolean, holdPlay = false, private playFailure: Error | null = null) {
    if (holdPlay) this.pendingPlay = () => {};
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
  resolve(blob: Blob): void;
  resolveResponse(response: TtsSpeechResponse): void;
  reject(error: unknown): void;
}

function setup(options: { blocked?: boolean; holdPlay?: boolean; playError?: Error; streaming?: boolean } = {}) {
  const requests: PendingRequest[] = [];
  const audios: FakeAudio[] = [];
  const mediaSources: FakeMediaSource[] = [];
  const revoked: string[] = [];
  let urlSeq = 0;

  const deps: TtsPlaybackDeps = {
    speak: (text, signal) =>
      new Promise<TtsSpeechResponse>((resolve, reject) => {
        requests.push({
          text,
          signal,
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
      const audio = new FakeAudio(options.blocked ?? false, options.holdPlay ?? false, options.playError ?? null);
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
  };

  return { controller: new TtsPlaybackController(deps), requests, audios, mediaSources, revoked };
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

    requests[0].resolve(new Blob(['mp3']));
    await done;

    expect(controller.getSnapshot()).toEqual({ state: 'playing', activeKey: 'm1', error: null });
    expect(audios).toHaveLength(1);
    expect(audios[0].src).toBe('blob:1');
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
    const { controller, requests } = setup({ blocked: true });
    const done = controller.speak('m1', 'hello');
    requests[0].resolve(new Blob(['mp3']));
    await done;

    await controller.resume();
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
