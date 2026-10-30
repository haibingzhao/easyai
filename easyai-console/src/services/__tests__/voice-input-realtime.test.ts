import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useVoiceInput } from '@/hooks/useVoiceInput';
import type { DictationSink, DictationSlotHandle } from '@/hooks/dictation-sink';
import { asrService } from '@/services/asr-service';
import { AsrBackpressureError, AsrRealtimeSession } from '@/services/asr-realtime-service';
import type { AsrRealtimeEvent } from '@/services/asr-realtime-service';

// Keep the real reason→notice table: only the network calls are replaced here.
vi.mock('@/services/asr-service', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/services/asr-service')>();
  return {
    ...actual,
    asrService: {
      availability: vi.fn(),
      transcribeSegment: vi.fn(),
      refineText: vi.fn(),
    },
  };
});

vi.mock('@/services/asr-realtime-service', () => ({
  AsrBackpressureError: class AsrBackpressureError extends Error {},
  AsrRealtimeSession: { open: vi.fn() },
  MAX_AUDIO_FRAME_BYTES: 3_200,
}));

const WEBSOCKET_AVAILABILITY = {
  configured: true,
  transport: 'WEBSOCKET' as const,
  capability: 'REALTIME_STREAMING' as const,
  capabilitySource: 'CATALOG' as const,
  modelId: 'qwen-audio-3.1-asr-flash-streaming',
  reason: null,
  hint: null,
  refineReady: false,
};

interface FakeSlot extends DictationSlotHandle {
  id: number;
  text: string;
  writes: Array<{ text: string; final: boolean }>;
}

class FakeSink implements DictationSink {
  readonly orderedCommits = false;
  readonly slots: FakeSlot[] = [];

  beginRound() {}

  openSlot(): FakeSlot {
    const slot: FakeSlot = {
      id: this.slots.length,
      text: '',
      writes: [],
      getText: () => slot.text,
      setText: (text, final) => {
        slot.text = text;
        slot.writes.push({ text, final });
      },
      remove: () => undefined,
      isConnected: () => true,
    };
    this.slots.push(slot);
    return slot;
  }

  contextAround() { return { before: '', after: '' }; }
  roundText(slots: DictationSlotHandle[]) { return slots.map((slot) => slot.getText()).join(''); }
  replaceRound(slots: DictationSlotHandle[], text: string) {
    slots.forEach((slot, index) => slot.setText(index === 0 ? text : '', true));
  }

  texts() { return this.slots.map((slot) => slot.text); }
  finals() { return this.slots.flatMap((slot) => slot.writes.filter((write) => write.final)); }
}

interface FakeClient {
  sendAudio: ReturnType<typeof vi.fn>;
  finish: ReturnType<typeof vi.fn>;
  cancel: ReturnType<typeof vi.fn>;
  dispose: ReturnType<typeof vi.fn>;
  isTerminated: boolean;
}

let sink: FakeSink;
let client: FakeClient;
let emit: (event: AsrRealtimeEvent) => void;
let workletPort: { onmessage: ((event: { data: unknown }) => void) | null; postMessage: ReturnType<typeof vi.fn> };
let stopSessions: Array<() => Promise<void>>;

async function harness() {
  const onError = vi.fn<(message: string) => void>();
  let voice: ReturnType<typeof useVoiceInput> | undefined;
  function Harness() {
    voice = useVoiceInput({ sink, onError, isRefineEnabled: () => false });
    return null;
  }
  renderToStaticMarkup(createElement(Harness));
  if (!voice) throw new Error('Harness did not capture useVoiceInput');
  stopSessions.push(voice.stopAndFinish);

  const frames = (count: number) => {
    for (let index = 0; index < count; index++) {
      workletPort.onmessage?.({ data: new Float32Array(1600).fill(0.2) });
    }
  };
  return { voice, onError, frames };
}

function stubEnvironment(sampleRate = 16_000) {
  const stopTrack = vi.fn();
  const track = { stop: stopTrack, addEventListener: vi.fn() };
  const stream = { getTracks: () => [track], getAudioTracks: () => [track] };
  const getUserMedia = vi.fn(async () => stream);
  workletPort = { onmessage: null, postMessage: vi.fn() };

  class FakeAudioContext {
    state = 'running';
    onstatechange: (() => void) | null = null;
    destination = {};
    audioWorklet = { addModule: vi.fn(async () => undefined) };
    createMediaStreamSource = vi.fn(() => ({ connect: vi.fn() }));
    createAnalyser = vi.fn(() => ({ fftSize: 0, connect: vi.fn() }));
    close = vi.fn(async () => undefined);

    constructor(readonly options?: { sampleRate?: number }) {
      this.sampleRate = options?.sampleRate ?? sampleRate;
    }

    sampleRate: number;
  }

  class FakeAudioWorkletNode {
    port = workletPort;
    connect = vi.fn();
  }

  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia } });
  vi.stubGlobal('AudioContext', FakeAudioContext);
  vi.stubGlobal('AudioWorkletNode', FakeAudioWorkletNode);
  vi.stubGlobal('document', {
    visibilityState: 'visible',
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });
  return { getUserMedia, stopTrack };
}

beforeEach(() => {
  vi.useFakeTimers();
  sink = new FakeSink();
  stopSessions = [];
  vi.mocked(asrService.availability).mockReset();
  vi.mocked(asrService.availability).mockResolvedValue(WEBSOCKET_AVAILABILITY);
  client = {
    sendAudio: vi.fn(),
    finish: vi.fn(),
    cancel: vi.fn(),
    dispose: vi.fn(),
    isTerminated: false,
  };
  emit = () => undefined;
  vi.mocked(AsrRealtimeSession.open).mockReset();
  vi.mocked(AsrRealtimeSession.open).mockImplementation((handlers) => {
    emit = handlers.onEvent;
    // The service sends `ready` during the handshake; the hook must not invite speech before it.
    handlers.onEvent({ type: 'ready', modelId: WEBSOCKET_AVAILABILITY.modelId });
    return Promise.resolve(client as unknown as AsrRealtimeSession);
  });
});

afterEach(async () => {
  try {
    const stopping = stopSessions.map((stop) => stop());
    await vi.advanceTimersByTimeAsync(3_000);
    await Promise.allSettled(stopping);
  } finally {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  }
});

describe('useVoiceInput channel selection', () => {
  it('never opens the microphone when the server reports no channel', async () => {
    vi.mocked(asrService.availability).mockResolvedValue({
      ...WEBSOCKET_AVAILABILITY,
      configured: true,
      transport: 'NONE',
      capability: 'NONE',
      reason: 'FILE_ONLY_MODEL',
    });
    const { getUserMedia } = stubEnvironment();
    const { voice, onError } = await harness();

    await voice.start();

    expect(getUserMedia).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalledWith(expect.stringContaining('uploaded files'));
    expect(AsrRealtimeSession.open).not.toHaveBeenCalled();
  });

  it('treats a failed availability read as unavailable instead of guessing', async () => {
    vi.mocked(asrService.availability).mockRejectedValue(new Error('HTTP 500'));
    const { getUserMedia } = stubEnvironment();
    const { voice, onError } = await harness();

    await voice.start();

    expect(getUserMedia).not.toHaveBeenCalled();
    expect(onError).toHaveBeenCalled();
    expect(AsrRealtimeSession.open).not.toHaveBeenCalled();
  });

  it('asks for a newer server when the availability endpoint does not exist', async () => {
    vi.mocked(asrService.availability).mockResolvedValue({
      configured: false,
      transport: 'NONE',
      capability: 'NONE',
      capabilitySource: null,
      modelId: null,
      reason: 'BACKEND_TOO_OLD',
      hint: null,
      refineReady: false,
    });
    stubEnvironment();
    const { voice, onError } = await harness();

    await voice.start();

    expect(onError).toHaveBeenCalledWith(expect.stringContaining('newer server'));
  });
});

describe('useVoiceInput streaming round', () => {
  it('waits for ready before producing audio, then streams whole 100 ms frames', async () => {
    stubEnvironment();
    const { voice, frames } = await harness();

    await voice.start();
    // The worklet is unlocked only once the service is listening.
    expect(workletPort.postMessage).toHaveBeenCalledWith({ type: 'flow-control' });

    frames(1);
    expect(client.sendAudio).toHaveBeenCalledTimes(1);
    expect(client.sendAudio.mock.calls[0][0]).toHaveLength(1600);
    // One credit returned per rendered block keeps the worklet producing.
    expect(workletPort.postMessage).toHaveBeenCalledWith({ type: 'credits', count: 1 });
  });

  it('sends nothing while the socket is still connecting', async () => {
    stubEnvironment();
    const { voice, frames } = await harness();
    // A handshake that never reaches ready leaves the round in `connecting`.
    vi.mocked(AsrRealtimeSession.open).mockImplementation(() => new Promise(() => undefined));

    const starting = voice.start();
    await vi.advanceTimersByTimeAsync(0);
    frames(3);
    expect(client.sendAudio).not.toHaveBeenCalled();
    expect(workletPort.postMessage).not.toHaveBeenCalledWith({ type: 'flow-control' });
    starting.catch(() => undefined);
  });

  it('replaces a sentence in place and writes its final once', async () => {
    stubEnvironment();
    const { voice } = await harness();
    await voice.start();

    emit({ type: 'sentence', sentenceId: 1, text: '帮我看', isFinal: false });
    emit({ type: 'sentence', sentenceId: 1, text: '帮我看一下', isFinal: false });
    emit({ type: 'sentence', sentenceId: 1, text: '帮我看一下英特尔', isFinal: true });
    emit({ type: 'sentence', sentenceId: 1, text: '重复的最终结果', isFinal: true });

    expect(sink.texts()).toEqual(['帮我看一下英特尔']);
    expect(sink.finals()).toEqual([{ text: '帮我看一下英特尔', final: true }]);
  });

  it('keeps a later sentence separate from an earlier one', async () => {
    stubEnvironment();
    const { voice } = await harness();
    await voice.start();

    emit({ type: 'sentence', sentenceId: 1, text: '第一句。', isFinal: true });
    emit({ type: 'sentence', sentenceId: 2, text: '第二句', isFinal: false });
    emit({ type: 'sentence', sentenceId: 2, text: '第二句。', isFinal: true });

    expect(sink.texts()).toEqual(['第一句。', '第二句。']);
  });

  it('flushes the partial frame before finish and returns to idle on done', async () => {
    stubEnvironment();
    const { voice } = await harness();
    await voice.start();

    // 1000 samples < one 1600-sample frame, so the tail only exists at stop.
    workletPort.onmessage?.({ data: new Float32Array(1000).fill(0.2) });
    expect(client.sendAudio).not.toHaveBeenCalled();

    const stopping = voice.stopAndFinish();
    expect(client.sendAudio).toHaveBeenCalledTimes(1);
    expect(client.sendAudio.mock.calls[0][0]).toHaveLength(1000);
    expect(client.finish).toHaveBeenCalledTimes(1);

    emit({ type: 'done', usage: null, notice: null });
    await stopping;
    expect(client.dispose).toHaveBeenCalled();
  });

  it('cancels upstream and keeps the visible text when done never arrives', async () => {
    stubEnvironment();
    const { voice } = await harness();
    await voice.start();
    emit({ type: 'sentence', sentenceId: 1, text: '停止前已经出来的字', isFinal: false });

    const stopping = voice.stopAndFinish();
    await vi.advanceTimersByTimeAsync(3_000);
    await stopping;

    expect(client.cancel).toHaveBeenCalled();
    // Unfinished sentences keep their text and are closed so the editor can commit them.
    expect(sink.texts()).toEqual(['停止前已经出来的字']);
    expect(sink.finals()).toEqual([{ text: '停止前已经出来的字', final: true }]);
    expect(client.dispose).toHaveBeenCalled();
  });

  it('stops the round when the socket buffer cannot keep up', async () => {
    stubEnvironment();
    const { voice, frames } = await harness();
    await voice.start();
    client.sendAudio.mockImplementation(() => { throw new AsrBackpressureError(); });

    frames(1);

    expect(client.dispose).toHaveBeenCalled();
    // Audio stops with the round: no further frames reach the socket.
    client.sendAudio.mockClear();
    frames(2);
    expect(client.sendAudio).not.toHaveBeenCalled();
  });

  it('reports an error frame with its code and keeps the partial transcript', async () => {
    stubEnvironment();
    const { voice, onError } = await harness();
    await voice.start();
    emit({ type: 'sentence', sentenceId: 1, text: '部分结果', isFinal: false });

    emit({ type: 'error', code: 'BACKPRESSURE', message: 'too slow', partial: true });

    expect(onError).toHaveBeenCalledWith(expect.stringContaining('BACKPRESSURE'));
    expect(sink.texts()).toEqual(['部分结果']);
    expect(sink.finals()).toEqual([{ text: '部分结果', final: true }]);
  });

  it('treats a socket that closes without done as an incomplete round', async () => {
    stubEnvironment();
    const { voice, onError } = await harness();
    await voice.start();
    emit({ type: 'sentence', sentenceId: 1, text: '已经显示的文字', isFinal: false });

    emit({ type: 'closed', code: 1006 });

    expect(onError).toHaveBeenCalledWith(expect.stringContaining('closed unexpectedly'));
    expect(sink.texts()).toEqual(['已经显示的文字']);
    expect(sink.finals()).toEqual([{ text: '已经显示的文字', final: true }]);
  });

  it('sends a cancelled round home without inventing text', async () => {
    stubEnvironment();
    const { voice } = await harness();
    const stopping = voice.start();
    await vi.advanceTimersByTimeAsync(0);
    await stopping;

    const finishing = voice.stopAndFinish();
    emit({ type: 'cancelled' });
    await finishing;

    expect(sink.texts()).toEqual([]);
    expect(client.dispose).toHaveBeenCalled();
  });
});
