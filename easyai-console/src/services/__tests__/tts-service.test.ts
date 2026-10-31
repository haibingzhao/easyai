import { beforeEach, describe, expect, it, vi } from 'vitest';
import { authFetch } from '@/services/api-client';
import { TtsError, ttsService, type SpeechRenderEvent } from '../tts-service';

vi.mock('@/services/api-client', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/services/api-client')>()),
  authFetch: vi.fn(),
}));

beforeEach(() => vi.resetAllMocks());

describe('ttsService.speak', () => {
  it.each([
    { mode: 'direct' as const },
    { mode: 'refine' as const },
  ])('sends $mode mode and preserves the abort signal', async ({ mode }) => {
    vi.mocked(authFetch).mockResolvedValue(new Response('audio', {
      headers: { 'Content-Type': 'audio/mpeg' },
    }));
    const controller = new AbortController();

    const result = await ttsService.speak('hello', controller.signal, mode);

    expect(result.mimeType).toBe('audio/mpeg');
    const [, options] = vi.mocked(authFetch).mock.calls[0];
    expect(JSON.parse(options?.body as string)).toEqual({ text: 'hello', mode });
    expect(options?.signal).toBe(controller.signal);
  });

  it('sends the message target so a refined read can be archived', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response('audio', {
      headers: { 'Content-Type': 'audio/mpeg' },
    }));
    const target = { type: 'session_message' as const, id: 'msg-1', sessionId: 'sess-1' };

    await ttsService.speak('hello', undefined, 'refine', target);

    const [, options] = vi.mocked(authFetch).mock.calls[0];
    expect(JSON.parse(options?.body as string)).toEqual({
      text: 'hello',
      mode: 'refine',
      targetType: 'session_message',
      targetId: 'msg-1',
      sessionId: 'sess-1',
    });
  });

  it('passes through the reason a refined read fell back to the original', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response('audio', {
      headers: { 'Content-Type': 'audio/mpeg', 'X-Speech-Fallback': 'length-limit' },
    }));

    const result = await ttsService.speak('hello', undefined, 'refine');

    expect(result.fallbackReason).toBe('length-limit');
  });

  it('defaults old callers to direct mode', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response('audio', {
      headers: { 'Content-Type': 'audio/mpeg' },
    }));
    await ttsService.speak('hello');
    const [, options] = vi.mocked(authFetch).mock.calls[0];
    expect(JSON.parse(options?.body as string)).toEqual({ text: 'hello', mode: 'direct' });
  });
});

/** A response body that arrives in the given pieces, the way a chunked stream does. */
function ndjsonResponse(chunks: string[]): Response {
  const encoder = new TextEncoder();
  return ndjsonByteResponse(chunks.map((chunk) => encoder.encode(chunk)));
}

/** Byte-level chunks, so a boundary can fall inside a multi-byte character. */
function ndjsonByteResponse(chunks: Uint8Array[]): Response {
  const body = new ReadableStream<Uint8Array>({
    start(controller) {
      chunks.forEach((chunk) => controller.enqueue(chunk));
      controller.close();
    },
  });
  return new Response(body, { headers: { 'Content-Type': 'application/x-ndjson' } });
}

const meta = '{"type":"meta","scriptId":"s1","revision":1,"segmentCount":2,"planned":true,"warnings":[]}';
const audio = {
  type: 'audio', segmentId: 'seg_0', index: 0, spokenText: '第一句。', voice: 'v',
  pauseAfterMs: 0, mimeType: 'audio/mpeg', data: 'AAA=', words: [],
};

describe('ttsService.render', () => {
  it('reassembles events by newline when a chunk boundary falls inside a line', async () => {
    // The split lands mid-object and mid-UTF-8 on purpose: a client that assumed one event per
    // chunk, or decoded each chunk on its own, would lose the second event (or mangle 「第一句」).
    const payload = `${meta}\n${JSON.stringify(audio)}\n{"type":"done","segments":2,"warnings":[]}\n`;
    const bytes = new TextEncoder().encode(payload);
    const prefix = new TextEncoder().encode(payload.slice(0, payload.indexOf('第一句。')));
    const cut = prefix.length + 2;
    vi.mocked(authFetch).mockResolvedValue(
      ndjsonByteResponse([bytes.slice(0, cut), bytes.slice(cut)])
    );

    const events: SpeechRenderEvent[] = [];
    await ttsService.render({ text: '第一句。' }, undefined, (event) => {
      events.push(event);
    });

    expect(events.map((event) => event.type)).toEqual(['meta', 'audio', 'done']);
    expect(events[1]).toMatchObject({ segmentId: 'seg_0', spokenText: '第一句。' });
  });

  it('sends the plan request with the defaults the server expects', async () => {
    vi.mocked(authFetch).mockResolvedValue(ndjsonResponse([`${meta}\n`]));

    await ttsService.render({ text: 'hello' }, undefined, () => undefined);

    const [url, options] = vi.mocked(authFetch).mock.calls[0];
    expect(url).toBe('/api/tts/render');
    // `segments: null` rather than omitted: the server distinguishes "no edits" from "empty plan".
    expect(JSON.parse(options?.body as string)).toEqual({
      text: 'hello',
      scriptId: null,
      segments: null,
      incremental: true,
    });
  });

  it('renders a stored plan by id with the edited segments', async () => {
    vi.mocked(authFetch).mockResolvedValue(ndjsonResponse([`${meta}\n`]));
    const segments = [{
      id: 'seg_0', index: 0, sourceText: '原文。', spokenText: '改过的。', voice: 'v',
      role: 'NARRATION', pauseAfterMs: 120,
    }];

    await ttsService.render({ scriptId: 's1', segments, incremental: false }, undefined, () => undefined);

    const [, options] = vi.mocked(authFetch).mock.calls[0];
    expect(JSON.parse(options?.body as string)).toMatchObject({
      scriptId: 's1',
      segments,
      incremental: false,
    });
  });

  it('skips a truncated line instead of failing the whole stream', async () => {
    vi.mocked(authFetch).mockResolvedValue(ndjsonResponse([
      `${meta}\n{"type":"aud`,
    ]));

    const events: SpeechRenderEvent[] = [];
    await ttsService.render({ text: 'x' }, undefined, (event) => {
      events.push(event);
    });

    // The meta event survived; the half-written line is dropped, and the caller still learns the
    // stream never said `done`, which is what a truncated response looks like.
    expect(events.map((event) => event.type)).toEqual(['meta']);
  });

  it('turns a refused request into a TtsError carrying the status', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response('{"error":"no director"}', {
      status: 409,
      headers: { 'Content-Type': 'application/json' },
    }));

    await expect(ttsService.render({ text: 'x' }, undefined, () => undefined))
      .rejects.toMatchObject({ name: 'TtsError', status: 409 });
  });

  it('refuses a 200 that is not the event stream', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response('<html>', {
      headers: { 'Content-Type': 'text/html' },
    }));

    await expect(ttsService.render({ text: 'x' }, undefined, () => undefined))
      .rejects.toBeInstanceOf(TtsError);
  });

  it('ignores a line that is valid JSON but not an event', async () => {
    // `null` and `42` parse fine and mean nothing; handing one to the consumer would throw inside
    // its `switch` and turn a single stray line into a dead stream.
    vi.mocked(authFetch).mockResolvedValue(ndjsonResponse([`null\n42\n[]\n${meta}\n`]));

    const events: SpeechRenderEvent[] = [];
    await ttsService.render({ text: 'x' }, undefined, (event) => {
      events.push(event);
    });

    expect(events.map((event) => event.type)).toEqual(['meta']);
  });

  it('releases the connection instead of leaving a non-stream body unread', async () => {
    let cancelled = 0;
    const body = new ReadableStream<Uint8Array>({
      start(controller) {
        controller.enqueue(new TextEncoder().encode('<html>'));
      },
      cancel() {
        cancelled++;
      },
    });
    vi.mocked(authFetch).mockResolvedValue(
      new Response(body, { headers: { 'Content-Type': 'text/html' } })
    );

    await expect(ttsService.render({ text: 'x' }, undefined, () => undefined))
      .rejects.toBeInstanceOf(TtsError);
    // An unread body keeps the response open; the error path has to close it.
    expect(cancelled).toBe(1);
  });
  it('waits for the handler before parsing the next line', async () => {
    // This is the memory bound: the reader stops reading while the player is full, which stops the
    // response being consumed, which is what eventually back-pressures synthesis.
    vi.mocked(authFetch).mockResolvedValue(ndjsonResponse([`${meta}\n${JSON.stringify(audio)}\n`]));
    const seen: string[] = [];
    let openNext: (() => void) | null = null;

    const done = ttsService.render({ text: 'x' }, undefined, async (event) => {
      seen.push(event.type);
      if (event.type === 'meta') await new Promise<void>((resolve) => { openNext = resolve; });
    });
    await Promise.resolve();
    await Promise.resolve();

    expect(seen).toEqual(['meta']);
    openNext!();
    await done;
    expect(seen).toEqual(['meta', 'audio']);
  });
});

describe('ttsService.script', () => {
  it('returns the plan without synthesizing', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response(JSON.stringify({
      scriptId: 's1',
      revision: 1,
      style: 'TEACHER',
      mood: '克制',
      segments: [],
      warnings: [],
      expiresInSeconds: 600,
    }), { headers: { 'Content-Type': 'application/json' } }));

    const script = await ttsService.script('原文。');

    expect(script.scriptId).toBe('s1');
    const [url, options] = vi.mocked(authFetch).mock.calls[0];
    expect(url).toBe('/api/tts/script');
    expect(JSON.parse(options?.body as string)).toEqual({ text: '原文。' });
  });

  it('reports the status code the panel needs to explain the failure', async () => {
    vi.mocked(authFetch).mockResolvedValue(new Response('', { status: 413 }));

    await expect(ttsService.script('x')).rejects.toMatchObject({ status: 413 });
  });
});
