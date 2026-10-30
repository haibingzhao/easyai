import { createElement } from 'react';
import { renderToStaticMarkup } from 'react-dom/server';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useVoiceInput } from '@/hooks/useVoiceInput';
import type { DictationSink, DictationSlotHandle } from '@/hooks/dictation-sink';
import { asrService } from '@/services/asr-service';

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

/** The HTTP channel is what these cases cover; the streaming channel has its own suite. */
const HTTP_AVAILABILITY = {
  configured: true,
  transport: 'HTTP' as const,
  capability: 'HTTP_SEGMENT' as const,
  capabilitySource: 'CATALOG' as const,
  modelId: 'qwen-audio-3.1-asr-flash',
  reason: null,
  hint: null,
  refineReady: false,
};

interface PendingTranscription {
  blob: Blob;
  resolve: (text: string) => void;
  reject: (error: Error) => void;
}

type SinkEvent =
  | { kind: 'draft' | 'final'; slot: number; text: string }
  | { kind: 'remove'; slot: number };

interface RecordedSlot extends DictationSlotHandle {
  id: number;
  text: string;
}

/** Only models the destination: no segmentation, ordering or conflict logic is duplicated here. */
class RecordingSink implements DictationSink {
  readonly slots: RecordedSlot[] = [];
  readonly visibleSlots: RecordedSlot[] = [];
  readonly events: SinkEvent[] = [];

  constructor(readonly orderedCommits: boolean) {}

  beginRound() {}

  openSlot(): RecordedSlot {
    const slot: RecordedSlot = {
      id: this.slots.length,
      text: '',
      getText: () => slot.text,
      setText: (text, final) => {
        if (!this.visibleSlots.includes(slot)) return;
        slot.text = text;
        this.events.push({ kind: final ? 'final' : 'draft', slot: slot.id, text });
      },
      remove: () => {
        this.events.push({ kind: 'remove', slot: slot.id });
        const index = this.visibleSlots.indexOf(slot);
        if (index >= 0) this.visibleSlots.splice(index, 1);
      },
      isConnected: () => this.visibleSlots.includes(slot),
    };
    this.slots.push(slot);
    this.visibleSlots.push(slot);
    return slot;
  }

  contextAround() { return { before: '', after: '' }; }
  roundText(slots: DictationSlotHandle[]) { return slots.map((slot) => slot.getText()).join(''); }
  replaceRound(slots: DictationSlotHandle[], text: string) {
    slots.forEach((slot, index) => slot.setText(index === 0 ? text : '', true));
  }

  texts() { return this.visibleSlots.map((slot) => slot.text); }
  finals() { return this.events.filter((event) => event.kind === 'final'); }
  removals() { return this.events.filter((event) => event.kind === 'remove'); }
}

let requests: PendingTranscription[];
let stopSessions: Array<() => Promise<void>>;

/** Flush promise continuations without running future timers or an unbounded timer loop. */
async function flush() {
  await vi.advanceTimersByTimeAsync(0);
}

async function setup(orderedCommits = false) {
  const sink = new RecordingSink(orderedCommits);
  const onError = vi.fn<(message: string) => void>();
  const stopTrack = vi.fn();
  const track = { stop: stopTrack, addEventListener: vi.fn() };
  const stream = { getTracks: () => [track], getAudioTracks: () => [track] };
  const contexts: FakeAudioContext[] = [];
  const worklets: FakeAudioWorkletNode[] = [];

  class FakeAudioContext {
    sampleRate = 16_000;
    state = 'running';
    onstatechange: (() => void) | null = null;
    destination = {};
    audioWorklet = { addModule: vi.fn(async () => undefined) };
    createMediaStreamSource = vi.fn(() => ({ connect: vi.fn() }));
    createAnalyser = vi.fn(() => ({ fftSize: 0, connect: vi.fn() }));
    close = vi.fn(async () => undefined);

    constructor() { contexts.push(this); }
  }

  class FakeAudioWorkletNode {
    port: { onmessage: ((event: Pick<MessageEvent<Float32Array>, 'data'>) => void) | null } = {
      onmessage: null,
    };
    connect = vi.fn();

    constructor() { worklets.push(this); }
  }

  vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: vi.fn(async () => stream) } });
  vi.stubGlobal('AudioContext', FakeAudioContext);
  vi.stubGlobal('AudioWorkletNode', FakeAudioWorkletNode);
  vi.stubGlobal('document', {
    visibilityState: 'visible',
    addEventListener: vi.fn(),
    removeEventListener: vi.fn(),
  });

  // SSR captures ref-backed callbacks; browser tests cover rerenders and lifecycle.
  let voice: ReturnType<typeof useVoiceInput> | undefined;
  function Harness() {
    voice = useVoiceInput({ sink, onError, isRefineEnabled: () => false });
    return null;
  }
  renderToStaticMarkup(createElement(Harness));
  if (!voice) throw new Error('Harness did not capture useVoiceInput');
  stopSessions.push(voice.stopAndFinish);
  await voice.start();
  expect(onError).not.toHaveBeenCalled();
  expect(worklets).toHaveLength(1);

  const frames = (count: number, amplitude = 0.2) => {
    const worklet = worklets[worklets.length - 1];
    for (let i = 0; i < count; i++) {
      if (!worklet.port.onmessage) throw new Error('Microphone is not capturing');
      // One real PCM frame: 1600 samples / 16 kHz = 100 ms.
      worklet.port.onmessage({ data: new Float32Array(1600).fill(amplitude) });
    }
  };
  return { voice, sink, onError, frames, stopTrack, contexts, worklets };
}

beforeEach(() => {
  vi.useFakeTimers();
  requests = [];
  stopSessions = [];
  vi.mocked(asrService.transcribeSegment).mockReset();
  vi.mocked(asrService.refineText).mockReset();
  vi.mocked(asrService.availability).mockReset();
  vi.mocked(asrService.availability).mockResolvedValue(HTTP_AVAILABILITY);
  vi.mocked(asrService.transcribeSegment).mockImplementation((blob) =>
    new Promise<string>((resolve, reject) => {
      requests.push({ blob, resolve, reject });
    }),
  );
});

afterEach(async () => {
  try {
    // SSR has no effect cleanup; explicitly stop every captured session, even after a failed assertion.
    const stopping = stopSessions.map((stop) => stop());
    requests.forEach((request) => request.resolve(''));
    await vi.advanceTimersByTimeAsync(2500);
    await Promise.all(stopping);
  } finally {
    vi.clearAllTimers();
    vi.useRealTimers();
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  }
});

describe.each([
  { name: 'chat-style sink', orderedCommits: false },
  { name: 'ordered editor sink', orderedCommits: true },
])('useVoiceInput / $name', ({ orderedCommits }) => {
  it('shows a draft that arrives after the pause but before the correction commits', async () => {
    const { frames, sink } = await setup(orderedCommits);
    frames(20);
    frames(6, 0);
    expect(requests.map(({ blob }) => blob.size)).toEqual([64_044, 83_244]);
    expect(sink.texts()).toEqual(['', '']);

    requests[0].resolve('迟到的草稿');
    await flush();
    expect(sink.texts()).toEqual(['迟到的草稿', '']);
    expect(sink.events).toEqual([{ kind: 'draft', slot: 0, text: '迟到的草稿' }]);

    requests[1].resolve('完整校正。');
    await flush();
    expect(sink.texts()).toEqual(['完整校正。', '']);
    expect(sink.finals()).toEqual([{ kind: 'final', slot: 0, text: '完整校正。' }]);
  });

  it('does not let a late draft overwrite a successful correction', async () => {
    const { frames, sink } = await setup(orderedCommits);
    frames(20);
    frames(6, 0);
    expect(requests).toHaveLength(2);

    requests[1].resolve('校正已经完成。');
    await flush();
    requests[0].resolve('更晚的旧草稿');
    await flush();
    expect(sink.texts()).toEqual(['校正已经完成。', '']);
    expect(sink.events).toEqual([{ kind: 'final', slot: 0, text: '校正已经完成。' }]);
  });

  it.each(['empty', 'failed'] as const)(
    'waits for every draft after an early %s correction, then finalizes the joined draft once',
    async (outcome) => {
      const { frames, sink, onError } = await setup(orderedCommits);
      frames(40);
      frames(6, 0);
      expect(requests.map(({ blob }) => blob.size)).toEqual([64_044, 64_044, 147_244]);
      if (outcome === 'empty') requests[2].resolve('');
      else requests[2].reject(new Error('HTTP 503'));
      await flush();
      expect(sink.events).toEqual([]);

      // Resolve fragments in reverse issue order. Even the first response is useful as a preview.
      requests[1].resolve('后半句');
      await flush();
      expect(sink.texts()).toEqual(['后半句', '']);
      expect(sink.finals()).toEqual([]);

      requests[0].resolve('前半句');
      await flush();
      expect(sink.texts()).toEqual(['前半句后半句', '']);
      expect(sink.events).toEqual([
        { kind: 'draft', slot: 0, text: '后半句' },
        { kind: 'draft', slot: 0, text: '前半句后半句' },
        { kind: 'final', slot: 0, text: '前半句后半句' },
      ]);
      expect(onError).not.toHaveBeenCalled();
    },
  );

  it.each([20, 150])('makes zero requests for %i silent frames, including on stop', async (count) => {
    const { frames, voice, sink } = await setup(orderedCommits);
    frames(count, 0);
    await flush();
    expect(requests).toHaveLength(0);
    await voice.stopAndFinish();
    expect(asrService.transcribeSegment).not.toHaveBeenCalled();
    expect(sink.events).toEqual([{ kind: 'remove', slot: 0 }]);
    expect(sink.texts()).toEqual([]);
  });

  it('ignores long silence after a sentence and still captures the next sentence', async () => {
    const { frames, sink, voice } = await setup(orderedCommits);
    frames(10);
    frames(6, 0);
    expect(requests).toHaveLength(1);
    requests[0].resolve('第一句。');
    await flush();

    frames(150, 0);
    await flush();
    expect(requests).toHaveLength(1);
    expect(sink.slots).toHaveLength(2);

    frames(10);
    frames(6, 0);
    expect(requests.map(({ blob }) => blob.size)).toEqual([51_244, 51_244]);
    requests[1].resolve('第二句。');
    await flush();
    await voice.stopAndFinish();
    expect(requests).toHaveLength(2);
    expect(sink.texts()).toEqual(['第一句。', '第二句。']);
    expect(sink.finals()).toEqual([
      { kind: 'final', slot: 0, text: '第一句。' },
      { kind: 'final', slot: 1, text: '第二句。' },
    ]);
    expect(sink.removals()).toEqual([{ kind: 'remove', slot: 2 }]);
  });

  it('retains an empty slot awaiting a stop-time correction so late rounds cannot reorder it', async () => {
    const { frames, sink, voice } = await setup(orderedCommits);
    frames(10); // No 2-second draft; stop must keep this still-empty sentence slot.
    const firstStop = voice.stopAndFinish();
    expect(requests.map(({ blob }) => blob.size)).toEqual([32_044]);
    expect(sink.removals()).toEqual([]);
    expect(sink.slots[0].isConnected()).toBe(true);
    await vi.advanceTimersByTimeAsync(2500);
    await firstStop;

    await voice.start();
    frames(10);
    const secondStop = voice.stopAndFinish();
    expect(requests).toHaveLength(2);
    requests[1].resolve('新一轮。');
    await flush();
    await secondStop;
    expect(sink.texts()).toEqual(['', '新一轮。']);

    requests[0].resolve('上一轮。');
    await flush();
    expect(sink.removals()).toEqual([]);
    expect(sink.visibleSlots.map((slot) => slot.id)).toEqual([0, 1]);
    expect(sink.texts()).toEqual(['上一轮。', '新一轮。']);
    expect(sink.finals()).toHaveLength(2);
  });

  it.each(['success', 'empty', 'failed'] as const)(
    'preserves manual edits against both a late draft and a %s correction',
    async (outcome) => {
      const { frames, sink, onError } = await setup(orderedCommits);
      frames(20);
      requests[0].resolve('已经显示的草稿');
      await flush();
      frames(20);
      frames(6, 0);
      expect(requests).toHaveLength(3);
      // A destination-side edit is intentionally not recorded as an ASR write.
      sink.slots[0].text = '用户亲自修改';
      if (outcome === 'failed') requests[2].reject(new Error('HTTP 503'));
      else requests[2].resolve(outcome === 'success' ? '不能覆盖用户的校正' : '');
      await flush();
      requests[1].resolve('不能覆盖用户的迟到草稿');
      await flush();

      expect(sink.texts()).toEqual(['用户亲自修改', '']);
      expect(sink.events).toEqual([{ kind: 'draft', slot: 0, text: '已经显示的草稿' }]);
      expect(onError).not.toHaveBeenCalled();
    },
  );

  it('releases capture immediately, stops waiting at 2.5 seconds, and accepts late correction', async () => {
    const { frames, sink, voice, stopTrack, contexts, worklets } = await setup(orderedCommits);
    frames(20);
    requests[0].resolve('停止前的草稿');
    await flush();
    let stopped = false;
    const stopping = voice.stopAndFinish().then(() => { stopped = true; });
    expect(stopTrack).toHaveBeenCalledTimes(1);
    expect(contexts[0].close).toHaveBeenCalledTimes(1);
    expect(worklets[0].port.onmessage).toBeNull();
    expect(voice.analyserRef.current).toBeNull();
    expect(requests).toHaveLength(2);

    await vi.advanceTimersByTimeAsync(2499);
    expect(stopped).toBe(false);
    expect(sink.texts()).toEqual(['停止前的草稿']);
    await vi.advanceTimersByTimeAsync(1);
    await stopping;
    expect(stopped).toBe(true);
    expect(sink.finals()).toEqual([]);
    expect(vi.getTimerCount()).toBe(0);

    requests[1].resolve('停止后迟到的校正。');
    await flush();
    expect(sink.texts()).toEqual(['停止后迟到的校正。']);
    expect(sink.finals()).toEqual([{ kind: 'final', slot: 0, text: '停止后迟到的校正。' }]);
    expect(stopTrack).toHaveBeenCalledTimes(1);
  });

  it('returns early and clears the stop timer when the correction finishes promptly', async () => {
    const { frames, voice, sink } = await setup(orderedCommits);
    frames(10);
    let stopped = false;
    const stopping = voice.stopAndFinish().then(() => { stopped = true; });
    await flush();
    expect(stopped).toBe(false);
    requests[0].resolve('及时完成。');
    await flush();
    expect(stopped).toBe(true);
    await stopping;
    expect(vi.getTimerCount()).toBe(0);
    expect(sink.finals()).toEqual([{ kind: 'final', slot: 0, text: '及时完成。' }]);
  });
});

describe('useVoiceInput sentence scheduling', () => {
  it('lets chat correct the later sentence first without changing slot order', async () => {
    const { frames, sink } = await setup(false);
    frames(10);
    frames(6, 0);
    frames(10);
    frames(6, 0);
    expect(requests.map(({ blob }) => blob.size)).toEqual([51_244, 51_244]);

    requests[1].resolve('第二句。');
    await flush();
    expect(sink.texts()).toEqual(['', '第二句。', '']);
    expect(sink.finals()).toEqual([{ kind: 'final', slot: 1, text: '第二句。' }]);
    requests[0].resolve('第一句。');
    await flush();
    expect(sink.texts()).toEqual(['第一句。', '第二句。', '']);
    expect(sink.finals()).toEqual([
      { kind: 'final', slot: 1, text: '第二句。' },
      { kind: 'final', slot: 0, text: '第一句。' },
    ]);
    expect(sink.removals()).toEqual([]);
  });

  it('issues ordered-sink transcriptions concurrently but commits in spoken order', async () => {
    const { frames, sink } = await setup(true);
    frames(20);
    frames(6, 0);
    frames(20);
    frames(6, 0);
    // Neither sentence correction may wait for the previous sentence's network response.
    expect(requests.map(({ blob }) => blob.size)).toEqual([64_044, 83_244, 64_044, 83_244]);

    requests[3].resolve('第二句校正。');
    await flush();
    expect(sink.finals()).toEqual([]);
    // Resolved recognition alone must not finalize a slot whose ordered commit is still blocked.
    requests[2].resolve('第二句草稿');
    await flush();
    expect(sink.texts()).toEqual(['', '第二句草稿', '']);
    expect(sink.finals()).toEqual([]);

    requests[1].resolve('第一句校正。');
    await flush();
    expect(sink.finals()).toEqual([
      { kind: 'final', slot: 0, text: '第一句校正。' },
      { kind: 'final', slot: 1, text: '第二句校正。' },
    ]);
    requests[0].resolve('太迟的第一句草稿');
    await flush();
    expect(sink.texts()).toEqual(['第一句校正。', '第二句校正。', '']);
    expect(sink.finals()).toHaveLength(2);
  });

  it('checks manual conflicts when an ordered commit executes, not when recognition resolves', async () => {
    const { frames, sink } = await setup(true);
    frames(10);
    frames(6, 0);
    frames(10);
    frames(6, 0);
    expect(requests).toHaveLength(2);
    requests[1].resolve('第二句校正');
    await flush();
    sink.slots[1].text = '等待时用户修改';
    requests[0].resolve('第一句。');
    await flush();
    expect(sink.texts()).toEqual(['第一句。', '等待时用户修改', '']);
    expect(sink.finals()).toEqual([{ kind: 'final', slot: 0, text: '第一句。' }]);
  });

  it('flushes real WAV drafts every 2 seconds and forces a fresh sentence at 10 seconds', async () => {
    const { frames, sink } = await setup();
    frames(19);
    expect(requests).toHaveLength(0);
    frames(1);
    expect(requests.map(({ blob }) => blob.size)).toEqual([64_044]);
    frames(60);
    expect(requests).toHaveLength(4);
    frames(19);
    expect(requests).toHaveLength(4);
    expect(sink.slots).toHaveLength(1);
    frames(1);
    expect(requests.map(({ blob }) => blob.size)).toEqual([
      64_044, 64_044, 64_044, 64_044, 64_044, 320_044,
    ]);
    expect(sink.slots).toHaveLength(2);

    // The encoder is not mocked: validate the mono 16 kHz / 16-bit PCM header as well as length.
    const wav = requests[5].blob;
    expect(wav.type).toBe('audio/wav');
    const bytes = await wav.arrayBuffer();
    const header = new DataView(bytes);
    expect(new TextDecoder().decode(bytes.slice(0, 4))).toBe('RIFF');
    expect(header.getUint16(22, true)).toBe(1);
    expect(header.getUint32(24, true)).toBe(16_000);
    expect(header.getUint16(34, true)).toBe(16);
    expect(header.getUint32(40, true)).toBe(320_000);

    for (const index of [4, 0, 3, 1, 2]) requests[index].resolve(String(index + 1));
    await flush();
    expect(sink.texts()).toEqual(['12345', '']);
    requests[5].resolve('十秒整句校正。');
    await flush();
    frames(20);
    expect(requests).toHaveLength(7);
    expect(requests[6].blob.size).toBe(64_044);
    requests[6].resolve('下一句草稿');
    await flush();
    expect(sink.texts()).toEqual(['十秒整句校正。', '下一句草稿']);
    expect(sink.finals()).toEqual([{ kind: 'final', slot: 0, text: '十秒整句校正。' }]);
  });
});
