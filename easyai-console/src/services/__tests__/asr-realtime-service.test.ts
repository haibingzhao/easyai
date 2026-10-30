import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import {
  AsrBackpressureError,
  AsrRealtimeSession,
  MAX_BUFFERED_BYTES,
  realtimeSocketUrl,
  type AsrRealtimeEvent,
  type AsrRealtimeTicket,
} from '../asr-realtime-service';

const TICKET: AsrRealtimeTicket = {
  ticket: 'ticket-abc',
  recordingId: 'rec-1',
  expiresInSeconds: 30,
  webSocketPath: '/api/asr/realtime',
};

/** Minimal socket double: records frames, drives the handlers, exposes the byte budget. */
class FakeSocket {
  readyState = 1;
  bufferedAmount = 0;
  binaryType = '';
  readonly sent: Array<string | ArrayBuffer> = [];
  onmessage: ((event: { data: unknown }) => void) | null = null;
  onerror: (() => void) | null = null;
  onclose: ((event: { code: number }) => void) | null = null;
  readonly closed: Array<{ code: number; reason: string }> = [];

  send(data: string | ArrayBuffer): void {
    this.sent.push(data);
  }

  close(code = 1000, reason = ''): void {
    this.readyState = 3;
    this.closed.push({ code, reason });
  }

  receive(frame: Record<string, unknown>): void {
    this.onmessage?.({ data: JSON.stringify(frame) });
  }

  closeFromServer(code = 1006): void {
    this.readyState = 3;
    this.onclose?.({ code });
  }
}

let socket: FakeSocket;
let events: AsrRealtimeEvent[];

function open(overrides: Parameters<typeof AsrRealtimeSession.open>[1] = {}) {
  return AsrRealtimeSession.open(
    { onEvent: (event) => events.push(event) },
    {
      requestTicket: async () => TICKET,
      createSocket: () => socket as unknown as WebSocket,
      ...overrides,
    },
  );
}

/**
 * `open()` awaits the ticket request before it can attach socket handlers, so every test that drives
 * the socket has to wait for that microtask first — otherwise the frame it sends is simply lost.
 */
async function waitForHandler(key: 'onmessage' | 'onclose') {
  for (let attempt = 0; attempt < 20; attempt++) {
    if (socket[key]) return;
    await Promise.resolve();
  }
  throw new Error(`socket.${key} was never registered`);
}

/** Connect, acknowledge `ready`, and hand back the live session. */
async function connected() {
  const opening = open();
  await waitForHandler('onmessage');
  socket.receive({ type: 'ready', recordingId: 'rec-1', eventSeq: 1, modelId: 'qwen-audio-3.1-asr-flash-streaming' });
  return opening;
}

beforeEach(() => {
  vi.stubGlobal('location', { origin: 'http://localhost:5173' });
  socket = new FakeSocket();
  events = [];
});

afterEach(() => {
  vi.unstubAllGlobals();
  vi.useRealTimers();
});

describe('realtimeSocketUrl', () => {
  it('stays on the page origin and upgrades the scheme to ws(s)', () => {
    expect(realtimeSocketUrl('/api/asr/realtime', 'a b', 'https://mac-mini.example'))
      .toBe('wss://mac-mini.example/api/asr/realtime?ticket=a%20b');
    expect(realtimeSocketUrl('api/asr/realtime', 't', 'http://localhost:5173'))
      .toBe('ws://localhost:5173/api/asr/realtime?ticket=t');
  });

  it('refuses to build a URL without an origin', () => {
    expect(() => realtimeSocketUrl('/api/asr/realtime', 't', '')).toThrow();
  });
});

describe('AsrRealtimeSession handshake', () => {
  it('resolves only after the service reports ready', async () => {
    let resolved = false;
    const opening = open().then((session) => { resolved = true; return session; });
    await waitForHandler('onmessage');
    expect(resolved).toBe(false);
    expect(socket.sent).toHaveLength(0);

    socket.receive({ type: 'ready', modelId: 'qwen-audio-3.1-asr-flash-streaming' });
    const session = await opening;
    expect(resolved).toBe(true);
    expect(events).toEqual([{ type: 'ready', modelId: 'qwen-audio-3.1-asr-flash-streaming' }]);
    session.dispose();
  });

  it('rejects rather than sitting in connecting when the socket never becomes ready', async () => {
    vi.useFakeTimers();
    const opening = open({ connectTimeoutMs: 1_000 });
    await waitForHandler('onmessage');
    const assertion = expect(opening).rejects.toThrow(/did not become ready/);
    await vi.advanceTimersByTimeAsync(1_000);
    await assertion;
    expect(socket.closed).toHaveLength(1);
  });

  it('rejects when the socket closes before ready', async () => {
    const opening = open();
    await waitForHandler('onclose');
    const assertion = expect(opening).rejects.toThrow(/closed before it was ready/);
    socket.closeFromServer(1006);
    await assertion;
  });

  it('rejects a result that arrives before ready', async () => {
    const opening = open();
    await waitForHandler('onmessage');
    const assertion = expect(opening).rejects.toThrow(/before it was ready/);
    socket.receive({ type: 'sentence', sentenceId: 1, text: '太早', isFinal: false });
    await assertion;
  });
});

describe('AsrRealtimeSession audio', () => {
  it('counts frames and samples and sends the PCM bytes unchanged', async () => {
    const session = await connected();
    const frame = new Int16Array(1600).fill(7);
    session.sendAudio(frame);
    session.sendAudio(frame);
    expect(session.framesSent).toBe(2);
    expect(session.samplesSent).toBe(3200);
    expect((socket.sent[0] as ArrayBuffer).byteLength).toBe(3200);
    session.dispose();
  });

  it('sends finish with the counters the server verifies', async () => {
    const session = await connected();
    session.sendAudio(new Int16Array(1600));
    session.finish();
    session.finish();
    expect(socket.sent.filter((frame) => typeof frame === 'string')).toEqual([
      JSON.stringify({ type: 'finish', lastAudioSeq: 1, totalSamples: 1600 }),
    ]);
    session.dispose();
  });

  it('fails the round instead of dropping frames when the socket buffer is full', async () => {
    const session = await connected();
    socket.bufferedAmount = MAX_BUFFERED_BYTES - 100;
    expect(() => session.sendAudio(new Int16Array(1600))).toThrow(AsrBackpressureError);
    expect(session.framesSent).toBe(0);
    session.dispose();
  });

  it('rejects a frame the service would refuse for size', async () => {
    const session = await connected();
    expect(() => session.sendAudio(new Int16Array(1601))).toThrow(RangeError);
    session.dispose();
  });

  it('stops sending audio after cancel', async () => {
    const session = await connected();
    session.cancel();
    session.sendAudio(new Int16Array(1600));
    expect(session.framesSent).toBe(0);
    expect(socket.sent).toContain(JSON.stringify({ type: 'cancel' }));
    session.dispose();
  });
});

describe('AsrRealtimeSession downstream frames', () => {
  it('surfaces sentence snapshots in arrival order, including the post-final correction', async () => {
    const session = await connected();
    socket.receive({ type: 'sentence', sentenceId: 1, text: '帮我看', isFinal: false });
    socket.receive({ type: 'sentence', sentenceId: 1, text: '帮我看一下', isFinal: false });
    socket.receive({ type: 'sentence', sentenceId: 1, text: '帮我看一下。', isFinal: true });
    expect(events.filter((event) => event.type === 'sentence')).toEqual([
      { type: 'sentence', sentenceId: 1, text: '帮我看', isFinal: false },
      { type: 'sentence', sentenceId: 1, text: '帮我看一下', isFinal: false },
      { type: 'sentence', sentenceId: 1, text: '帮我看一下。', isFinal: true },
    ]);
    session.dispose();
  });

  it('ignores heartbeats, unknown frames and malformed JSON', async () => {
    const session = await connected();
    socket.receive({ type: 'pong' });
    socket.receive({ type: 'mystery' });
    socket.receive({ type: 'sentence', sentenceId: 0, text: '心跳不该建句', isFinal: false });
    socket.onmessage?.({ data: 'not json' });
    expect(events.filter((event) => event.type === 'sentence')).toHaveLength(0);
    session.dispose();
  });

  it('reports done with its usage and ignores anything after it', async () => {
    const session = await connected();
    socket.receive({
      type: 'done',
      usage: { inputTokens: 365, outputTokens: 12, totalTokens: 377, durationSeconds: null },
      notice: null,
    });
    socket.receive({ type: 'sentence', sentenceId: 2, text: '迟到的句子', isFinal: true });
    expect(events.filter((event) => event.type === 'done')).toEqual([
      { type: 'done', usage: { inputTokens: 365, outputTokens: 12, totalTokens: 377, durationSeconds: null }, notice: null },
    ]);
    expect(events.filter((event) => event.type === 'sentence')).toHaveLength(0);
    session.dispose();
  });

  it('reports an error frame with its code and partial flag', async () => {
    const session = await connected();
    socket.receive({ type: 'error', code: 'BACKPRESSURE', message: 'too slow', partial: true });
    expect(events[events.length - 1]).toEqual({ type: 'error', code: 'BACKPRESSURE', message: 'too slow', partial: true });
    session.dispose();
  });

  it('treats a clean close without done as an incomplete round', async () => {
    const session = await connected();
    socket.closeFromServer(1000);
    expect(events[events.length - 1]).toEqual({ type: 'closed', code: 1000 });
    session.dispose();
  });

  it('stops reporting after dispose', async () => {
    const session = await connected();
    session.dispose();
    socket.closeFromServer(1000);
    expect(events.filter((event) => event.type === 'closed')).toHaveLength(0);
  });
});
